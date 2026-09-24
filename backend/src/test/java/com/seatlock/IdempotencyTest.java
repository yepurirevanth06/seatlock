package com.seatlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.booking.BookingRepository;
import com.seatlock.booking.BookingService;
import com.seatlock.booking.BookingService.ConfirmResult;
import com.seatlock.common.ApiException;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.seat.Seat;
import com.seatlock.user.Role;
import com.seatlock.user.User;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/** Retries with the same Idempotency-Key must never create a second booking. */
class IdempotencyTest extends IntegrationTestBase {

    @Autowired TestData data;
    @Autowired SeatHoldService holds;
    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookings;

    @Test
    void retryingWithSameKeyReturnsTheOriginalBooking() {
        Seat seat = data.eventWithSeats(1, 1, 1500).get(0);
        User alice = data.user(Role.USER);
        String key = UUID.randomUUID().toString();
        holds.hold(seat.getEventId(), List.of(seat.getId()), alice.getId());

        ConfirmResult first = bookingService.confirm(alice.getId(), seat.getEventId(), List.of(seat.getId()), key);
        // Holds are gone now, so without idempotency this retry would fail with "hold expired".
        ConfirmResult retry = bookingService.confirm(alice.getId(), seat.getEventId(), List.of(seat.getId()), key);

        assertThat(first.replayed()).isFalse();
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.booking().id()).isEqualTo(first.booking().id());
        assertThat(bookings.countByEventId(seat.getEventId())).isEqualTo(1);
    }

    @Test
    void simultaneousRetriesWithSameKeyAllGetOneBooking() throws Exception {
        // Simulates a client that fires the same checkout 10 times at once (double-clicks plus retries).
        Seat seat = data.eventWithSeats(1, 1, 1500).get(0);
        User alice = data.user(Role.USER);
        String key = UUID.randomUUID().toString();
        holds.hold(seat.getEventId(), List.of(seat.getId()), alice.getId());
        Set<Long> bookingIds = ConcurrentHashMap.newKeySet();

        List<Boolean> results = Race.run(10, i -> () -> bookingIds.add(
                bookingService.confirm(alice.getId(), seat.getEventId(), List.of(seat.getId()), key).booking().id()));

        assertThat(Race.wins(results)).isEqualTo(10);   // every attempt got a successful response...
        assertThat(bookingIds).hasSize(1);              // ...describing the same single booking
        assertThat(bookings.countByEventId(seat.getEventId())).isEqualTo(1);
    }

    @Test
    void reusingAKeyForDifferentSeatsIsRejected() {
        List<Seat> grid = data.eventWithSeats(1, 2, 1000);
        Long eventId = grid.get(0).getEventId();
        User alice = data.user(Role.USER);
        String key = UUID.randomUUID().toString();

        holds.hold(eventId, List.of(grid.get(0).getId()), alice.getId());
        bookingService.confirm(alice.getId(), eventId, List.of(grid.get(0).getId()), key);

        holds.hold(eventId, List.of(grid.get(1).getId()), alice.getId());
        assertThatThrownBy(() -> bookingService.confirm(alice.getId(), eventId, List.of(grid.get(1).getId()), key))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void keysAreScopedPerUser() {
        // Two different users happening to send the same key must not see each other's bookings.
        List<Seat> grid = data.eventWithSeats(1, 2, 1000);
        Long eventId = grid.get(0).getEventId();
        User alice = data.user(Role.USER);
        User bob = data.user(Role.USER);
        String sharedKey = "checkout-1";

        holds.hold(eventId, List.of(grid.get(0).getId()), alice.getId());
        holds.hold(eventId, List.of(grid.get(1).getId()), bob.getId());

        ConfirmResult a = bookingService.confirm(alice.getId(), eventId, List.of(grid.get(0).getId()), sharedKey);
        ConfirmResult b = bookingService.confirm(bob.getId(), eventId, List.of(grid.get(1).getId()), sharedKey);

        assertThat(a.booking().id()).isNotEqualTo(b.booking().id());
        assertThat(b.replayed()).isFalse();
    }
}
