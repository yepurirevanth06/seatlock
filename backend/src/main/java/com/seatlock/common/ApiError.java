package com.seatlock.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        int status,
        String error,
        String message,
        List<Long> seatIds,
        Map<String, String> fieldErrors,
        Instant timestamp) {

    public static ApiError of(int status, String error, String message) {
        return new ApiError(status, error, message, null, null, Instant.now());
    }
}
