package com.seatlock.booking;

import com.seatlock.auth.AuthUser;
import com.seatlock.booking.BookingDtos.BookingResponse;
import com.seatlock.booking.BookingDtos.CreateBookingRequest;
import com.seatlock.booking.BookingService.ConfirmResult;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String REPLAYED = "Idempotent-Replayed";

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    /**
     * Clients should send a unique Idempotency-Key per checkout and reuse it on retries.
     * A replayed response carries "Idempotent-Replayed: true".
     */
    @PostMapping
    public ResponseEntity<BookingResponse> create(@Valid @RequestBody CreateBookingRequest request,
                                                  @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String key,
                                                  @AuthenticationPrincipal AuthUser user) {
        ConfirmResult result = bookingService.confirm(user.id(), request.eventId(), request.seatIds(), key);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(REPLAYED, String.valueOf(result.replayed()))
                .body(result.booking());
    }

    @GetMapping("/me")
    public List<BookingResponse> mine(@AuthenticationPrincipal AuthUser user) {
        return bookingService.forUser(user.id());
    }
}
