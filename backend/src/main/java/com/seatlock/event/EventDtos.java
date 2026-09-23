package com.seatlock.event;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class EventDtos {

    private EventDtos() {}

    public record CreateEventRequest(
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 200) String venue,
            @NotNull @Future Instant startsAt,
            @Min(1) @Max(26) int rows,
            @Min(1) @Max(40) int seatsPerRow,
            @Min(0) @Max(100_000) int priceCents) {}

    public record EventSummary(
            Long id, String name, String venue, Instant startsAt, long totalSeats, long availableSeats) {}

    /** Status as seen by the current viewer; HELD_BY_YOU is only ever returned to the holder. */
    public enum ViewerSeatStatus { AVAILABLE, HELD, HELD_BY_YOU, BOOKED }

    public record SeatView(Long id, String row, int number, int priceCents, ViewerSeatStatus status) {}

    public record SeatRow(String label, List<SeatView> seats) {}

    public record SeatMapResponse(Long eventId, List<SeatRow> rows, long holdTtlSeconds, int maxSeatsPerHold) {}

    public record SeatSelection(@NotEmpty @Size(max = 40) List<@NotNull Long> seatIds) {}

    public record HoldResponse(List<Long> seatIds, Instant expiresAt) {}

    public record ReleaseResponse(int released) {}
}
