package com.seatlock.booking;

import com.seatlock.common.NotFoundException;
import com.seatlock.common.SeatConflictException;
import com.seatlock.messaging.OutboxRepository;
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
 * Everything below happens in ONE transaction, so it all commits or none of it does:
 * the idempotency key, the seat locks, the booking, and the outbox event.
 */
@Service
public class BookingWriter {

    public record BookingResult(Booking booking, List<Seat> seats) {}

    private final SeatRepository seats;
    private final BookingRepository bookings;
    private final IdempotencyStore idempotency;
    private final OutboxRepository outbox;

    public BookingWriter(SeatRepository seats, BookingRepository bookings, IdempotencyStore idempotency,
                         OutboxRepository outbox) {
        this.seats = seats;
        this.bookings = bookings;
        this.idempotency = idempotency;
        this.outbox = outbox;
    }

    @Transactional
    public BookingResult finalizeBooking(Long userId, Long eventId, List<Long> seatIds) {
        return finalizeBooking(userId, eventId, seatIds, null, null);
    }

    @Transactional
    public BookingResult finalizeBooking(Long userId, Long eventId, List<Long> seatIds,
                                         String idempotencyKey, String requestHash) {
        // 0. Claim the idempotency key first. A concurrent retry with the same key queues up
        //    here (not on the seats) and fails with DuplicateKeyException once we commit.
        if (idempotencyKey != null) {
            idempotency.reserve(userId, idempotencyKey, requestHash);
        }

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

        // 3. Write the booking and point each seat at it.
        int total = locked.stream().mapToInt(Seat::getPriceCents).sum();
        Booking booking = bookings.save(new Booking(userId, eventId, total));
        locked.forEach(seat -> seat.markBooked(booking.getId()));
        seats.flush();

        // 4. Record the outcome for retries, and the event for the confirmation email.
        if (idempotencyKey != null) {
            idempotency.attach(userId, idempotencyKey, booking.getId());
        }
        outbox.add("BookingConfirmed", "{\"bookingId\":" + booking.getId() + "}");

        return new BookingResult(booking, locked);
    }
}
