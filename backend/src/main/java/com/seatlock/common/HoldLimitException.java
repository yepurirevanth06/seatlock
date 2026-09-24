package com.seatlock.common;

import org.springframework.http.HttpStatus;

/** The user already holds as many seats for this event as they are allowed. */
public class HoldLimitException extends ApiException {

    public HoldLimitException(long currentlyHeld, int max) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "You're already holding " + currentlyHeld
                + " seats for this event. The limit is " + max + " at a time, so book or release some first.");
    }
}
