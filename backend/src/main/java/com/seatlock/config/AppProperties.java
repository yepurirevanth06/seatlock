package com.seatlock.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed view of the "app.*" section in application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Holds holds, boolean seedDemoData, List<String> corsOrigins) {

    public record Jwt(String secret, long ttlMinutes) {}

    public record Holds(long ttlSeconds, int maxSeats) {}
}
