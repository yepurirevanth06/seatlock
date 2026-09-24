package com.seatlock.booking;

import com.seatlock.booking.BookingDtos.BookedSeat;
import com.seatlock.booking.BookingDtos.BookingResponse;
import com.seatlock.booking.BookingWriter.BookingResult;
import com.seatlock.common.NotFoundException;
import com.seatlock.common.SeatConflictException;
import com.seatlock.event.Event;
import com.seatlock.event.EventRepository;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.realtime.SeatBroadcaster;
import com.seatlock.seat.Seat;
import com.seatlock.seat.SeatRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout flow: the user must currently hold every seat (Redis), then the
 * database transaction makes it permanent (BookingWriter), then holds are cleared.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final SeatHoldService holds;
    private final BookingWriter writer;
    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final EventRepository events;
    private final SeatBroadcaster broadcaster;

    public BookingService(SeatHoldService holds, BookingWriter writer, BookingRepository bookings,
                          SeatRepository seats, EventRepository events, SeatBroadcaster broadcaster) {
        this.holds = holds;
        this.writer = writer;
        this.bookings = bookings;
        this.seats = seats;
        this.events = events;
        this.broadcaster = broadcaster;
    }

    public BookingResponse confirm(Long userId, Long eventId, List<Long> requestedSeatIds) {
        Event event = events.findById(eventId).orElseThrow(() -> new NotFoundException("Event not found"));
        List<Long> seatIds = holds.normalize(requestedSeatIds);

        List<Long> notHeld = holds.seatsNotHeldBy(eventId, seatIds, userId);
        if (!notHeld.isEmpty()) {
            throw new SeatConflictException(
                    "Your hold on some of these seats has expired. Select them again.", notHeld);
        }

        // Commits (or rolls back) before returning.
        BookingResult result = writer.finalizeBooking(userId, eventId, seatIds);

        try {
            holds.release(eventId, seatIds, userId);
        } catch (RuntimeException e) {
            // Not fatal: the seats are BOOKED in Postgres, which always wins, and the keys expire anyway.
            log.warn("Could not release holds for booking {}: {}", result.booking().getId(), e.getMessage());
        }

        // Only after the transaction has committed, so viewers never see a booking that rolled back.
        broadcaster.seatsChanged(eventId, seatIds);

        return toResponse(result.booking(), event, result.seats());
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> forUser(Long userId) {
        List<Booking> mine = bookings.findByUserIdOrderByCreatedAtDesc(userId);
        if (mine.isEmpty()) {
            return List.of();
        }
        Map<Long, List<Seat>> seatsByBooking = seats
                .findByBookingIdInOrderByRowLabelAscSeatNumberAsc(mine.stream().map(Booking::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(Seat::getBookingId));
        Map<Long, Event> eventsById = events
                .findAllById(mine.stream().map(Booking::getEventId).distinct().toList())
                .stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));

        return mine.stream()
                .map(b -> toResponse(b, eventsById.get(b.getEventId()),
                        seatsByBooking.getOrDefault(b.getId(), List.of())))
                .toList();
    }

    private static BookingResponse toResponse(Booking booking, Event event, List<Seat> bookedSeats) {
        List<BookedSeat> seatViews = bookedSeats.stream()
                .map(s -> new BookedSeat(s.getId(), s.getRowLabel(), s.getSeatNumber()))
                .toList();
        return new BookingResponse(booking.getId(), booking.getEventId(), event.getName(), event.getVenue(),
                event.getStartsAt(), seatViews, booking.getTotalCents(), booking.getCreatedAt());
    }
}
