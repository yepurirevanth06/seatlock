package com.seatlock.common;

import java.util.List;

/**
 * Thrown when one or more seats cannot be held or booked because someone else
 * got there first. Carries the seat ids so the client can highlight them.
 */
public class SeatConflictException extends ConflictException {

    private final List<Long> seatIds;

    public SeatConflictException(String message, List<Long> seatIds) {
        super(message);
        this.seatIds = List.copyOf(seatIds);
    }

    public List<Long> getSeatIds() {
        return seatIds;
    }
}
