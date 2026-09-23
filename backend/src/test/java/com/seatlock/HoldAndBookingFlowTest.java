package com.seatlock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatlock.booking.BookingDtos.BookingResponse;
import com.seatlock.booking.BookingService;
import com.seatlock.common.BadRequestException;
import com.seatlock.common.SeatConflictException;
import com.seatlock.event.EventDtos.SeatView;
import com.seatlock.event.EventDtos.ViewerSeatStatus;
import com.seatlock.event.EventService;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.seat.Seat;
import com.seatlock.user.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class HoldAndBookingFlowTest extends IntegrationTestBase {

    @Autowired TestData data;
    @Autowired SeatHoldService holds;
    @Autowired BookingService bookingService;
    @Autowired EventService eventService;

    @Test
    void holderCanBookAndOthersAreBlockedAtEveryStep() {
        List<Seat> grid = data.eventWithSeats(1, 4, 1200);
        Long eventId = grid.get(0).getEventId();
        List<Long> wanted = List.of(grid.get(0).getId(), grid.get(1).getId());
        User alice = data.user(com.seatlock.user.Role.USER);
        User bob = data.user(com.seatlock.user.Role.USER);

        holds.hold(eventId, wanted, alice.getId());

        // Bob cannot hold Alice's seats...
        assertThatThrownBy(() -> holds.hold(eventId, wanted, bob.getId()))
                .isInstanceOf(SeatConflictException.class);
        // ...and cannot skip the hold and book them directly.
        assertThatThrownBy(() -> bookingService.confirm(bob.getId(), eventId, wanted))
                .isInstanceOf(SeatConflictException.class);

        // The seat map tells each viewer a different, correct story.
        assertThat(statusOf(eventId, wanted.get(0), alice.getId())).isEqualTo(ViewerSeatStatus.HELD_BY_YOU);
        assertThat(statusOf(eventId, wanted.get(0), bob.getId())).isEqualTo(ViewerSeatStatus.HELD);

        BookingResponse booking = bookingService.confirm(alice.getId(), eventId, wanted);
        assertThat(booking.seats()).hasSize(2);
        assertThat(booking.totalCents()).isEqualTo(2400);

        // Booked is permanent and visible to everyone; the hold keys were cleaned up.
        assertThat(statusOf(eventId, wanted.get(0), bob.getId())).isEqualTo(ViewerSeatStatus.BOOKED);
        assertThat(holds.holders(eventId, wanted)).isEmpty();
        assertThat(bookingService.forUser(alice.getId())).extracting(BookingResponse::id).containsExactly(booking.id());
    }

    @Test
    void holdIsAllOrNothing() {
        List<Seat> grid = data.eventWithSeats(1, 3, 0);
        Long eventId = grid.get(0).getEventId();
        User alice = data.user(com.seatlock.user.Role.USER);
        User bob = data.user(com.seatlock.user.Role.USER);

        holds.hold(eventId, List.of(grid.get(1).getId()), alice.getId());

        // Bob asks for seats 1-3; seat 2 is taken, so he must get none of them.
        assertThatThrownBy(() -> holds.hold(eventId, grid.stream().map(Seat::getId).toList(), bob.getId()))
                .isInstanceOf(SeatConflictException.class)
                .satisfies(e -> assertThat(((SeatConflictException) e).getSeatIds()).containsExactly(grid.get(1).getId()));
        assertThat(holds.holders(eventId, List.of(grid.get(0).getId(), grid.get(2).getId()))).isEmpty();
    }

    @Test
    void userCannotReleaseSomeoneElsesHold() {
        Seat seat = data.eventWithSeats(1, 1, 0).get(0);
        User alice = data.user(com.seatlock.user.Role.USER);
        User bob = data.user(com.seatlock.user.Role.USER);

        holds.hold(seat.getEventId(), List.of(seat.getId()), alice.getId());

        assertThat(holds.release(seat.getEventId(), List.of(seat.getId()), bob.getId())).isZero();
        assertThat(holds.holders(seat.getEventId(), List.of(seat.getId()))).containsEntry(seat.getId(), alice.getId());
    }

    @Test
    void holdSizeIsCapped() {
        List<Seat> grid = data.eventWithSeats(1, 10, 0);
        User alice = data.user(com.seatlock.user.Role.USER);

        assertThatThrownBy(() -> holds.hold(grid.get(0).getEventId(),
                grid.stream().map(Seat::getId).toList(), alice.getId()))
                .isInstanceOf(BadRequestException.class);
    }

    private ViewerSeatStatus statusOf(Long eventId, Long seatId, Long viewerId) {
        return eventService.seatMap(eventId, viewerId).rows().stream()
                .flatMap(row -> row.seats().stream())
                .filter(s -> s.id().equals(seatId))
                .map(SeatView::status)
                .findFirst()
                .orElseThrow();
    }
}
