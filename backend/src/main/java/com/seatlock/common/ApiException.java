package com.seatlock.common;

import org.springframework.http.HttpStatus;

/** Base class for errors that map directly to an HTTP status and a user-facing message. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
