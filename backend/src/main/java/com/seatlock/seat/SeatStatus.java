package com.seatlock.seat;

/**
 * Durable seat state stored in Postgres. Temporary holds live only in Redis,
 * so a seat is never stuck as "held" in the database if a user walks away.
 */
public enum SeatStatus {
    AVAILABLE,
    BOOKED
}
