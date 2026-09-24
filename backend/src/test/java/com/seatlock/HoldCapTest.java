package com.seatlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.booking.BookingService;
import com.seatlock.common.HoldLimitException;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.seat.Seat;
import com.seatlock.user.Role;
import com.seatlock.user.User;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

/** One user may hold at most 6 seats per event in total, across any number of requests or tabs. */
class HoldCapTest extends IntegrationTestBase {

    @Autowired TestData data;
    @Autowired SeatHoldService holds;
    @Autowired BookingService bookingService;
    @Autowired StringRedisTemplate redis;

    @Test
    void capAppliesAcrossSeparateRequests() {
        List<Long> seats = ids(data.eventWithSeats(1, 10, 0));
        Long eventId = eventOf(seats);
        User alice = data.user(Role.USER);

        holds.hold(eventId, seats.subList(0, 4), alice.getId());
        holds.hold(eventId, seats.subList(4, 6), alice.getId()); // exactly 6 total

        assertThatThrownBy(() -> holds.hold(eventId, seats.subList(6, 7), alice.getId()))
                .isInstanceOf(HoldLimitException.class)
                .hasMessageContaining("already holding 6");
    }

    @Test
    void reholdingYourOwnSeatsDoesNotCountTwice() {
        List<Long> seats = ids(data.eventWithSeats(1, 6, 0));
        Long eventId = eventOf(seats);
        User alice = data.user(Role.USER);

        holds.hold(eventId, seats, alice.getId());
        holds.hold(eventId, seats, alice.getId()); // refreshes the hold, still 6 seats
    }

    @Test
    void releasedAndExpiredHoldsFreeUpCapacity() {
        List<Long> seats = ids(data.eventWithSeats(1, 10, 0));
        Long eventId = eventOf(seats);
        User alice = data.user(Role.USER);
        holds.hold(eventId, seats.subList(0, 6), alice.getId());

        holds.release(eventId, seats.subList(0, 2), alice.getId());
        // Simulate one hold expiring: Redis deletes the key on its own.
        redis.delete("hold:{event:" + eventId + "}:seat:" + seats.get(2));

        holds.hold(eventId, seats.subList(6, 9), alice.getId()); // 3 left + 3 new = 6
    }

    @Test
    void bookingFreesCapacity() {
        List<Long> seats = ids(data.eventWithSeats(1, 12, 0));
        Long eventId = eventOf(seats);
        User alice = data.user(Role.USER);

        holds.hold(eventId, seats.subList(0, 6), alice.getId());
        bookingService.confirm(alice.getId(), eventId, seats.subList(0, 6));

        holds.hold(eventId, seats.subList(6, 12), alice.getId());
    }

    @Test
    void capIsPerEvent() {
        List<Long> first = ids(data.eventWithSeats(1, 6, 0));
        List<Long> second = ids(data.eventWithSeats(1, 6, 0));
        User alice = data.user(Role.USER);

        holds.hold(eventOf(first), first, alice.getId());
        holds.hold(eventOf(second), second, alice.getId());
    }

    @Test
    void simultaneousRequestsFromOneUserCannotBeatTheCap() throws Exception {
        // 20 tabs each grab a different seat at the same instant. Only 6 may win.
        List<Long> seats = ids(data.eventWithSeats(1, 20, 0));
        Long eventId = eventOf(seats);
        User alice = data.user(Role.USER);
        AtomicInteger capped = new AtomicInteger();

        List<Boolean> results = Race.run(20, i -> () -> {
            try {
                holds.hold(eventId, List.of(seats.get(i)), alice.getId());
                return true;
            } catch (HoldLimitException e) {
                capped.incrementAndGet();
                return false;
            }
        });

        assertThat(results).hasSize(20);
        assertThat(capped.get()).isEqualTo(14);
        assertThat(holds.holders(eventId, seats).values()).hasSize(6).allMatch(alice.getId()::equals);
    }

    private static List<Long> ids(List<Seat> seats) {
        return seats.stream().map(Seat::getId).toList();
    }

    private Long eventOf(List<Long> seatIds) {
        return data.eventIdOfSeat(seatIds.get(0));
    }
}
