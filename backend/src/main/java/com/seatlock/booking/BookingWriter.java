package com.seatlock.booking;

import com.seatlock.common.NotFoundException;
import com.seatlock.common.SeatConflictException;
import com.seatlock.seat.Seat;
import com.seatlock.seat.SeatRepository;
import com.seatlock.seat.SeatStatus;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only code path that turns seats into a booking.
 *
 * This is the final guarantee against double booking and does not trust Redis at all:
 * even if a hold expired a millisecond ago, or Redis was flushed, two transactions
 * cannot both book the same seat because the second one blocks on the row lock
 * and then sees the seat already BOOKED.
 *
 * It lives in its own bean so the @Transactional proxy applies when BookingService
 * calls it, and so the transaction commits before holds are released.
 */
@Service
public class BookingWriter {

    public record BookingResult(Booking booking, List<Seat> seats) {}

    private final SeatRepository seats;
    private final BookingRepository bookings;

    public BookingWriter(SeatRepository seats, BookingRepository bookings) {
        this.seats = seats;
        this.bookings = bookings;
    }

    @Transactional
    public BookingResult finalizeBooking(Long userId, Long eventId, List<Long> seatIds) {
        List<Long> sortedIds = seatIds.stream().distinct().sorted().toList();

        // 1. Lock the rows (in id order). Competing transactions queue up here.
        List<Seat> locked = seats.lockForBooking(eventId, sortedIds);
        if (locked.size() != sortedIds.size()) {
            throw new NotFoundException("One or more seats do not exist for this event");
        }

        // 2. With the locks held, the status we read cannot change underneath us.
        List<Long> taken = locked.stream()
                .filter(s -> s.getStatus() != SeatStatus.AVAILABLE)
                .map(Seat::getId)
                .toList();
        if (!taken.isEmpty()) {
            throw new SeatConflictException("Some of these seats were just booked by someone else", taken);
        }

        // 3. Write the booking and point each seat at it. Commit releases the locks.
        int total = locked.stream().mapToInt(Seat::getPriceCents).sum();
        Booking booking = bookings.save(new Booking(userId, eventId, total));
        locked.forEach(seat -> seat.markBooked(booking.getId()));
        seats.flush();

        return new BookingResult(booking, locked);
    }
}
