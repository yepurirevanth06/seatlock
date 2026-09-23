/*
  The "concert drop" test: many users rush a small event at the same moment.

  Setup creates a fresh 100-seat event and registers N users. Then every user,
  all at once, tries to hold and book a random seat (up to 3 attempts).

  Pass criteria:
    1. seats_booked never exceeds the seats that exist (threshold below)
    2. seats_booked equals the BOOKED count the API reports afterwards (printed in teardown)
    3. scripts/verify-no-double-booking.sql reports zero orphaned bookings

  Run:  k6 run -e USERS=500 load-test/contested-booking.js
*/
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = Number(__ENV.USERS || 500);
const ROWS = 5;
const SEATS_PER_ROW = 20;
const TOTAL_SEATS = ROWS * SEATS_PER_ROW;
const RUN_ID = Date.now();
const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 409 Conflict is the expected, correct answer for losers of a race, not a failure.
http.setResponseCallback(http.expectedStatuses(200, 201, 409));

const seatsBooked = new Counter('seats_booked');
const holdConflicts = new Counter('hold_conflicts');
const bookConflicts = new Counter('book_conflicts');
const bookingLatency = new Trend('booking_latency', true);

export const options = {
  setupTimeout: '5m',
  scenarios: {
    rush: { executor: 'per-vu-iterations', vus: USERS, iterations: 1, maxDuration: '3m' },
  },
  thresholds: {
    seats_booked: [`count<=${TOTAL_SEATS}`],
    http_req_failed: ['rate<0.01'],
    booking_latency: ['p(95)<500'],
  },
};

const auth = (token) => ({ headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } });

export function setup() {
  const adminLogin = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ email: __ENV.ADMIN_EMAIL || 'admin@seatlock.dev', password: __ENV.ADMIN_PASSWORD || 'admin12345' }),
    { headers: JSON_HEADERS },
  );
  check(adminLogin, { 'admin signed in': (r) => r.status === 200 });
  const adminToken = adminLogin.json('token');

  const created = http.post(
    `${BASE}/api/events`,
    JSON.stringify({
      name: `Load test ${RUN_ID}`,
      venue: 'k6 Arena',
      startsAt: new Date(Date.now() + 7 * 864e5).toISOString(),
      rows: ROWS,
      seatsPerRow: SEATS_PER_ROW,
      priceCents: 1000,
    }),
    auth(adminToken),
  );
  check(created, { 'event created': (r) => r.status === 201 });
  const eventId = created.json('id');

  const map = http.get(`${BASE}/api/events/${eventId}/seats`).json();
  const seatIds = map.rows.flatMap((row) => row.seats.map((s) => s.id));

  const tokens = [];
  for (let start = 0; start < USERS; start += 50) {
    const batch = [];
    for (let i = start; i < Math.min(start + 50, USERS); i++) {
      batch.push([
        'POST',
        `${BASE}/api/auth/register`,
        JSON.stringify({ email: `k6-${RUN_ID}-${i}@example.com`, password: 'loadtest-password', displayName: `k6 ${i}` }),
        { headers: JSON_HEADERS },
      ]);
    }
    http.batch(batch).forEach((r) => tokens.push(r.json('token')));
  }
  return { eventId, seatIds, tokens };
}

export default function (data) {
  const token = data.tokens[__VU - 1];
  for (let attempt = 0; attempt < 3; attempt++) {
    const seatId = data.seatIds[Math.floor(Math.random() * data.seatIds.length)];

    const hold = http.post(`${BASE}/api/events/${data.eventId}/holds`, JSON.stringify({ seatIds: [seatId] }), auth(token));
    if (hold.status === 409) {
      holdConflicts.add(1);
      continue;
    }
    if (hold.status !== 200) return;

    const started = Date.now();
    const book = http.post(`${BASE}/api/bookings`, JSON.stringify({ eventId: data.eventId, seatIds: [seatId] }), auth(token));
    bookingLatency.add(Date.now() - started);

    if (book.status === 201) {
      seatsBooked.add(1);
      return;
    }
    if (book.status === 409) bookConflicts.add(1);
  }
}

export function teardown(data) {
  const map = http.get(`${BASE}/api/events/${data.eventId}/seats`).json();
  const booked = map.rows.flatMap((r) => r.seats).filter((s) => s.status === 'BOOKED').length;
  console.log(`Event ${data.eventId}: API reports ${booked} of ${TOTAL_SEATS} seats BOOKED.`);
  console.log('This must equal the seats_booked counter in the summary below.');
}
