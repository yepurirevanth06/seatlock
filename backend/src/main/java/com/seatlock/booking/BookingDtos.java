package com.seatlock.booking;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class BookingDtos {

    private BookingDtos() {}

    public record CreateBookingRequest(
            @NotNull Long eventId,
            @NotEmpty @Size(max = 40) List<@NotNull Long> seatIds) {}

    public record BookedSeat(Long id, String row, int number) {}

    public record BookingResponse(
            Long id,
            Long eventId,
            String eventName,
            String venue,
            Instant startsAt,
            List<BookedSeat> seats,
            int totalCents,
            Instant createdAt) {}
}
