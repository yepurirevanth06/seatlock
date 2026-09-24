-- Idempotency keys: one row per (user, key). The primary key is what makes concurrent
-- retries safe: a second insert with the same key waits for the first transaction,
-- then fails, and the caller returns the booking the first one created.
CREATE TABLE idempotency_keys (
    user_id      BIGINT       NOT NULL REFERENCES users (id),
    idem_key     VARCHAR(100) NOT NULL,
    request_hash CHAR(64)     NOT NULL,
    booking_id   BIGINT REFERENCES bookings (id),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);

-- Transactional outbox: events are written in the same transaction as the booking,
-- so a committed booking always has its event, and a rolled-back one never does.
-- A background relay publishes unpublished rows to RabbitMQ.
CREATE TABLE outbox_events (
    id           BIGSERIAL PRIMARY KEY,
    event_type   VARCHAR(50) NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- Consumer-side deduplication: RabbitMQ delivers at least once, so the email
-- sender records each booking it has handled and skips repeats.
CREATE TABLE booking_notifications (
    booking_id BIGINT PRIMARY KEY REFERENCES bookings (id),
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
