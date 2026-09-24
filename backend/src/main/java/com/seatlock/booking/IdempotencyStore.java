package com.seatlock.booking;

import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Remembers which booking each (user, Idempotency-Key) produced, so a retried request
 * returns the original result instead of booking again.
 */
@Repository
public class IdempotencyStore {

    public record Entry(String requestHash, Long bookingId) {}

    private final JdbcTemplate jdbc;

    public IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Entry> find(Long userId, String key) {
        return jdbc.query(
                "SELECT request_hash, booking_id FROM idempotency_keys WHERE user_id = ? AND idem_key = ?",
                (rs, i) -> new Entry(rs.getString("request_hash"), (Long) rs.getObject("booking_id", Long.class)),
                userId, key).stream().findFirst();
    }

    /**
     * Claims the key inside the booking transaction. If another transaction already claimed it,
     * Postgres makes this insert wait for that transaction; if it commits, this throws
     * DuplicateKeyException, which the caller turns into a replay of the first result.
     */
    public void reserve(Long userId, String key, String requestHash) {
        jdbc.update("INSERT INTO idempotency_keys (user_id, idem_key, request_hash) VALUES (?, ?, ?)",
                userId, key, requestHash);
    }

    public void attach(Long userId, String key, Long bookingId) {
        jdbc.update("UPDATE idempotency_keys SET booking_id = ? WHERE user_id = ? AND idem_key = ?",
                bookingId, userId, key);
    }
}
