package com.seatlock.messaging;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    public record OutboxEvent(Long id, String eventType, String payload) {}

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the business transaction it describes. */
    public void add(String eventType, String payload) {
        jdbc.update("INSERT INTO outbox_events (event_type, payload) VALUES (?, ?)", eventType, payload);
    }

    /**
     * Claims a batch of unpublished events. SKIP LOCKED lets several app instances run the relay
     * at once without publishing the same row twice: each instance skips rows another has locked.
     */
    public List<OutboxEvent> lockUnpublished(int limit) {
        return jdbc.query("""
                SELECT id, event_type, payload FROM outbox_events
                WHERE published_at IS NULL
                ORDER BY id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new OutboxEvent(rs.getLong("id"), rs.getString("event_type"), rs.getString("payload")),
                limit);
    }

    public void markPublished(List<Long> ids) {
        jdbc.batchUpdate("UPDATE outbox_events SET published_at = now() WHERE id = ?",
                ids.stream().map(id -> new Object[] {id}).toList());
    }

    public long countUnpublished() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }
}
