package com.seatlock;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.booking.BookingRepository;
import com.seatlock.booking.BookingWriter;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.seat.Seat;
import com.seatlock.seat.SeatRepository;
import com.seatlock.seat.SeatStatus;
import com.seatlock.user.User;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The tests that back the headline claim of the project: under concurrent load,
 * a seat is never sold twice.
 */
class BookingConcurrencyTest extends IntegrationTestBase {

    @Autowired TestData data;
    @Autowired SeatHoldService holds;
    @Autowired BookingWriter writer;
    @Autowired SeatRepository seats;
    @Autowired BookingRepository bookings;
    @Autowired JdbcTemplate jdbc;

    @Test
    void exactlyOneOfFiftyUsersCanHoldTheSameSeat() throws Exception {
        Seat seat = data.eventWithSeats(1, 1, 1000).get(0);

        List<Boolean> results = Race.run(50,
                i -> () -> holds.hold(seat.getEventId(), List.of(seat.getId()), 10_000L + i));

        assertThat(Race.wins(results)).isEqualTo(1);
    }

    @Test
    void databaseAloneRejectsDoubleBookingWhenHoldsAreBypassed() throws Exception {
        // Simulates the worst case: holds expired or Redis lost data, and 50 checkouts
        // for the same seat reach the database at the same moment.
        Seat seat = data.eventWithSeats(1, 1, 1000).get(0);
        List<User> buyers = data.users(50);

        List<Boolean> results = Race.run(50,
                i -> () -> writer.finalizeBooking(buyers.get(i).getId(), seat.getEventId(), List.of(seat.getId())));

        assertThat(Race.wins(results)).isEqualTo(1);
        assertThat(bookings.countByEventId(seat.getEventId())).isEqualTo(1);

        Seat after = seats.findById(seat.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SeatStatus.BOOKED);
        assertThat(after.getBookingId()).isNotNull();
    }

    @Test
    void overlappingMultiSeatBookingsNeverDeadlockOrOverlap() throws Exception {
        // Seat sets are requested in conflicting orders ([a,b] vs [b,a]). Without consistent
        // lock ordering this is a textbook deadlock; Race.run would surface it as an exception.
        List<Seat> grid = data.eventWithSeats(1, 3, 500);
        Long eventId = grid.get(0).getEventId();
        Long a = grid.get(0).getId();
        Long b = grid.get(1).getId();
        Long c = grid.get(2).getId();
        List<List<Long>> requests = List.of(List.of(a, b), List.of(b, a), List.of(b, c), List.of(c, b), List.of(c, a));
        List<User> buyers = data.users(40);

        List<Boolean> results = Race.run(40,
                i -> () -> writer.finalizeBooking(buyers.get(i).getId(), eventId, requests.get(i % requests.size())));

        // Every seat is booked at most once, and every seat is owned by a booking that exists.
        List<Seat> after = seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId);
        long bookedSeats = after.stream().filter(s -> s.getStatus() == SeatStatus.BOOKED).count();
        long bookingsMade = bookings.countByEventId(eventId);

        assertThat(Race.wins(results)).isEqualTo(bookingsMade);
        assertThat(bookingsMade).isEqualTo(1); // every pair of requests shares a seat, so only one can win
        assertThat(bookedSeats).isEqualTo(2);
        assertThat(after.stream().map(Seat::getBookingId).filter(Objects::nonNull).distinct().count()).isEqualTo(1);
    }

    @Test
    void noBookingIsEverLeftWithoutSeats() throws Exception {
        // If a later booking had silently overwritten an earlier one, the earlier booking row
        // would exist with zero seats. Hammer a small event, then look for such orphans.
        List<Seat> grid = data.eventWithSeats(2, 5, 700);
        Long eventId = grid.get(0).getEventId();
        List<User> buyers = data.users(60);

        List<Boolean> results = Race.run(60, i -> {
            Seat target = grid.get(i % grid.size());
            return () -> writer.finalizeBooking(buyers.get(i).getId(), eventId, List.of(target.getId()));
        });

        Long orphans = jdbc.queryForObject("""
                SELECT count(*) FROM bookings b
                WHERE b.event_id = ? AND NOT EXISTS (SELECT 1 FROM seats s WHERE s.booking_id = b.id)
                """, Long.class, eventId);
        assertThat(orphans).isZero();
        assertThat(Race.wins(results)).isEqualTo(grid.size());
        assertThat(seats.countByEventIdAndStatus(eventId, SeatStatus.BOOKED)).isEqualTo(grid.size());
    }
}
