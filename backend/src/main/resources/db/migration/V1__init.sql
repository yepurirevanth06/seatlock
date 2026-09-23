-- Users who can sign in. Role is USER or ADMIN (admins create events).
CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE events (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(200) NOT NULL,
    venue      VARCHAR(200) NOT NULL,
    starts_at  TIMESTAMPTZ  NOT NULL,
    created_by BIGINT       NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE bookings (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id),
    event_id    BIGINT      NOT NULL REFERENCES events (id),
    total_cents INTEGER     NOT NULL CHECK (total_cents >= 0),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per physical seat. A seat can point at exactly one booking, so the
-- schema itself cannot represent "two bookings own the same seat". The
-- application's job is to make sure a second booking never silently
-- overwrites the first (see BookingWriter).
CREATE TABLE seats (
    id           BIGSERIAL PRIMARY KEY,
    event_id     BIGINT      NOT NULL REFERENCES events (id) ON DELETE CASCADE,
    row_label    VARCHAR(5)  NOT NULL,
    seat_number  INTEGER     NOT NULL,
    price_cents  INTEGER     NOT NULL CHECK (price_cents >= 0),
    status       VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'BOOKED')),
    booking_id   BIGINT REFERENCES bookings (id),
    CONSTRAINT uq_seat_position UNIQUE (event_id, row_label, seat_number),
    CONSTRAINT chk_booked_has_booking CHECK ((status = 'BOOKED') = (booking_id IS NOT NULL))
);

CREATE INDEX idx_seats_event ON seats (event_id);
CREATE INDEX idx_seats_booking ON seats (booking_id);
CREATE INDEX idx_bookings_user ON bookings (user_id);
CREATE INDEX idx_events_starts_at ON events (starts_at);
