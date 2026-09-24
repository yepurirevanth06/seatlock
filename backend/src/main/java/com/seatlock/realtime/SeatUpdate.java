package com.seatlock.realtime;

import java.util.List;

/**
 * Message pushed to /topic/events/{eventId}/seats whenever seats change.
 * Status is viewer-neutral (AVAILABLE, HELD, BOOKED); the browser that owns a hold
 * already knows which seats are its own.
 */
public record SeatUpdate(Long eventId, List<SeatChange> changes) {

    public enum PublicStatus { AVAILABLE, HELD, BOOKED }

    public record SeatChange(Long seatId, PublicStatus status) {}
}
