-- Run after a load test:
--   docker compose exec -T postgres psql -U seatlock -d seatlock < scripts/verify-no-double-booking.sql

-- 1. A booking whose seats were overwritten by a later booking would own zero seats. Expect 0.
SELECT count(*) AS orphaned_bookings
FROM bookings b
WHERE NOT EXISTS (SELECT 1 FROM seats s WHERE s.booking_id = b.id);

-- 2. Every booking's stored total must match the seats it owns. Expect no rows.
SELECT b.id AS mismatched_booking
FROM bookings b
JOIN seats s ON s.booking_id = b.id
GROUP BY b.id, b.total_cents
HAVING sum(s.price_cents) <> b.total_cents;

-- 3. Booked vs total seats per event, for eyeballing.
SELECT e.id, e.name,
       count(*) FILTER (WHERE s.status = 'BOOKED') AS booked,
       count(*) AS total
FROM events e
JOIN seats s ON s.event_id = e.id
GROUP BY e.id, e.name
ORDER BY e.id;
