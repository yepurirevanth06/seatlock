package com.seatlock.booking;

import com.seatlock.booking.BookingDtos.BookedSeat;
import com.seatlock.booking.BookingDtos.BookingResponse;
import com.seatlock.booking.BookingWriter.BookingResult;
import com.seatlock.common.ApiException;
import com.seatlock.common.BadRequestException;
import com.seatlock.common.ConflictException;
import com.seatlock.common.NotFoundException;
import com.seatlock.common.SeatConflictException;
import com.seatlock.event.Event;
import com.seatlock.event.EventRepository;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.realtime.SeatBroadcaster;
import com.seatlock.seat.Seat;
import com.seatlock.seat.SeatRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout flow: the user must currently hold every seat (Redis), then the
 * database transaction makes it permanent (BookingWriter), then holds are cleared.
 *
 * With an Idempotency-Key, repeating the same request (double-click, network retry)
 * returns the original booking instead of an error or a second booking.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final int MAX_KEY_LENGTH = 100;

    /** replayed = true when this response was returned from an earlier identical request. */
    public record ConfirmResult(BookingResponse booking, boolean replayed) {}

    private final SeatHoldService holds;
    private final BookingWriter writer;
    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final EventRepository events;
    private final SeatBroadcaster broadcaster;
    private final IdempotencyStore idempotency;

    public BookingService(SeatHoldService holds, BookingWriter writer, BookingRepository bookings,
                          SeatRepository seats, EventRepository events, SeatBroadcaster broadcaster,
                          IdempotencyStore idempotency) {
        this.holds = holds;
        this.writer = writer;
        this.bookings = bookings;
        this.seats = seats;
        this.events = events;
        this.broadcaster = broadcaster;
        this.idempotency = idempotency;
    }

    public BookingResponse confirm(Long userId, Long eventId, List<Long> requestedSeatIds) {
        return confirm(userId, eventId, requestedSeatIds, null).booking();
    }

    public ConfirmResult confirm(Long userId, Long eventId, List<Long> requestedSeatIds, String idempotencyKey) {
        Event event = events.findById(eventId).orElseThrow(() -> new NotFoundException("Event not found"));
        List<Long> seatIds = holds.normalize(requestedSeatIds);
        String key = validateKey(idempotencyKey);
        String requestHash = requestHash(eventId, seatIds);

        // A retry of a request that already succeeded: return the original booking.
        if (key != null) {
            Optional<ConfirmResult> replay = replay(userId, key, requestHash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        List<Long> notHeld = holds.seatsNotHeldBy(eventId, seatIds, userId);
        if (!notHeld.isEmpty()) {
            // The first attempt may have just finished and released the holds. Check once more.
            if (key != null) {
                Optional<ConfirmResult> replay = replay(userId, key, requestHash);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            throw new SeatConflictException(
                    "Your hold on some of these seats has expired. Select them again.", notHeld);
        }

        BookingResult result;
        try {
            // Commits (or rolls back) before returning.
            result = writer.finalizeBooking(userId, eventId, seatIds, key, requestHash);
        } catch (DuplicateKeyException sameKeyInFlight) {
            // A concurrent request with this key committed first. Hand back its booking.
            return replay(userId, key, requestHash).orElseThrow(() -> new ConflictException(
                    "A request with this Idempotency-Key is still being processed. Try again shortly."));
        }

        try {
            holds.release(eventId, seatIds, userId);
        } catch (RuntimeException e) {
            // Not fatal: the seats are BOOKED in Postgres, which always wins, and the keys expire anyway.
            log.warn("Could not release holds for booking {}: {}", result.booking().getId(), e.getMessage());
        }

        // Only after the transaction has committed, so viewers never see a booking that rolled back.
        broadcaster.seatsChanged(eventId, seatIds);

        return new ConfirmResult(toResponse(result.booking(), event, result.seats()), false);
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

    private Optional<ConfirmResult> replay(Long userId, String key, String requestHash) {
        return idempotency.find(userId, key).map(entry -> {
            if (!entry.requestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "This Idempotency-Key was already used for a different booking request");
            }
            if (entry.bookingId() == null) {
                throw new ConflictException("A request with this Idempotency-Key is still being processed.");
            }
            return new ConfirmResult(responseFor(entry.bookingId()), true);
        });
    }

    private BookingResponse responseFor(Long bookingId) {
        Booking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        Event event = events.findById(booking.getEventId())
                .orElseThrow(() -> new NotFoundException("Event not found"));
        return toResponse(booking, event, seats.findByBookingIdInOrderByRowLabelAscSeatNumberAsc(List.of(bookingId)));
    }

    private static String validateKey(String key) {
        if (key == null) {
            return null;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_KEY_LENGTH) {
            throw new BadRequestException("Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
        return trimmed;
    }

    /** Fingerprint of what was asked for, so a key cannot be reused for a different request. */
    static String requestHash(Long eventId, List<Long> sortedSeatIds) {
        String canonical = eventId + ":" + sortedSeatIds.stream().map(String::valueOf).collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static BookingResponse toResponse(Booking booking, Event event, List<Seat> bookedSeats) {
        List<BookedSeat> seatViews = bookedSeats.stream()
                .map(s -> new BookedSeat(s.getId(), s.getRowLabel(), s.getSeatNumber()))
                .toList();
        return new BookingResponse(booking.getId(), booking.getEventId(), event.getName(), event.getVenue(),
                event.getStartsAt(), seatViews, booking.getTotalCents(), booking.getCreatedAt());
    }
}
