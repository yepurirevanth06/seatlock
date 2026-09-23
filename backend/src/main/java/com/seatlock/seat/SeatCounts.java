package com.seatlock.seat;

/** Projection for per-event seat totals. */
public interface SeatCounts {
    Long getEventId();
    Long getTotal();
    Long getAvailable();
}
