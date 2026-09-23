package com.seatlock.booking;

import com.seatlock.auth.AuthUser;
import com.seatlock.booking.BookingDtos.BookingResponse;
import com.seatlock.booking.BookingDtos.CreateBookingRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookings")
public class BookingController {

    private final BookingService bookingService;

    public BookingController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse create(@Valid @RequestBody CreateBookingRequest request,
                                  @AuthenticationPrincipal AuthUser user) {
        return bookingService.confirm(user.id(), request.eventId(), request.seatIds());
    }

    @GetMapping("/me")
    public List<BookingResponse> mine(@AuthenticationPrincipal AuthUser user) {
        return bookingService.forUser(user.id());
    }
}
