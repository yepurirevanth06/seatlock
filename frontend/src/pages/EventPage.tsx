import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import HoldTimer from '../components/HoldTimer';
import SeatMap from '../components/SeatMap';
import { formatDate, formatPrice, formatTime, seatLabel } from '../format';
import { subscribeToSeats, type SeatUpdate } from '../realtime';
import type { Booking, EventSummary, SeatMap as SeatMapData, SeatView } from '../types';

// Seat changes arrive instantly over WebSocket. This slow refresh is only a safety net
// in case a message is missed while the connection is reconnecting.
const RESYNC_MS = 30000;

type Message = { kind: 'error' | 'info'; text: string } | null;

export default function EventPage() {
  const eventId = Number(useParams().id);
  const { user } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const [event, setEvent] = useState<EventSummary | null>(null);
  const [map, setMap] = useState<SeatMapData | null>(null);
  const [selected, setSelected] = useState<number[]>([]);
  const [expiresAt, setExpiresAt] = useState<string | null>(null);
  const [booking, setBooking] = useState<Booking | null>(null);
  const [message, setMessage] = useState<Message>(null);
  const [busy, setBusy] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [live, setLive] = useState(false);
  // Seats this browser is in the middle of holding. The server's broadcast can arrive
  // before our own HTTP response, and we must not mistake our own hold for someone else's.
  const holdingNow = useRef<Set<number>>(new Set());
  // One key per checkout attempt, reused if the booking request has to be retried.
  const checkoutKey = useRef<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setMap(await api.seatMap(eventId));
    } catch (e) {
      setLoadError((e as ApiError).message);
    }
  }, [eventId]);

  useEffect(() => {
    api.event(eventId).then(setEvent).catch((e: ApiError) => setLoadError(e.message));
    refresh();
    const id = window.setInterval(refresh, RESYNC_MS);
    return () => window.clearInterval(id);
  }, [eventId, refresh, user]);

  // Apply pushed changes directly to the map, keeping our own holds marked as ours.
  const applyUpdate = useCallback((update: SeatUpdate) => {
    const changed = new Map(update.changes.map((c) => [c.seatId, c.status]));
    setMap((current) => {
      if (!current) return current;
      return {
        ...current,
        rows: current.rows.map((row) => ({
          ...row,
          seats: row.seats.map((seat) => {
            const next = changed.get(seat.id);
            if (!next) return seat;
            const mine = seat.status === 'HELD_BY_YOU' || holdingNow.current.has(seat.id);
            return { ...seat, status: next === 'HELD' && mine ? 'HELD_BY_YOU' : next };
          }),
        })),
      };
    });
  }, []);

  useEffect(
    () =>
      subscribeToSeats(eventId, applyUpdate, (connected) => {
        setLive(connected);
        if (connected) refresh(); // catch up on anything missed while disconnected
      }),
    [eventId, applyUpdate, refresh],
  );

  const seatsById = useMemo(() => {
    const m = new Map<number, SeatView>();
    map?.rows.forEach((r) => r.seats.forEach((s) => m.set(s.id, s)));
    return m;
  }, [map]);

  const held = useMemo(
    () => [...seatsById.values()].filter((s) => s.status === 'HELD_BY_YOU'),
    [seatsById],
  );

  // If someone else grabs a seat we had only selected, drop it and say so.
  useEffect(() => {
    const lost = selected.filter((id) => seatsById.get(id)?.status !== 'AVAILABLE');
    if (lost.length) {
      setSelected((cur) => cur.filter((id) => !lost.includes(id)));
      setMessage({ kind: 'info', text: `${labels(lost)} just became unavailable.` });
    }
  }, [seatsById]);

  const labels = (ids: number[]) =>
    ids
      .map((id) => seatsById.get(id))
      .filter((s): s is SeatView => Boolean(s))
      .map(seatLabel)
      .join(', ') || 'Some seats';

  const maxSeats = map?.maxSeatsPerHold ?? 6;

  function onSeatClick(seat: SeatView) {
    if (!user) {
      navigate('/login', { state: { from: location.pathname } });
      return;
    }
    if (seat.status !== 'AVAILABLE') return;
    setMessage(null);
    setSelected((cur) => {
      if (cur.includes(seat.id)) return cur.filter((id) => id !== seat.id);
      if (cur.length >= maxSeats) {
        setMessage({ kind: 'info', text: `You can hold up to ${maxSeats} seats at a time.` });
        return cur;
      }
      return [...cur, seat.id];
    });
  }

  async function run(action: () => Promise<void>) {
    setBusy(true);
    setMessage(null);
    try {
      await action();
    } catch (e) {
      const err = e as ApiError;
      const text = err.seatIds.length ? `${err.message} (${labels(err.seatIds)})` : err.message;
      setMessage({ kind: 'error', text });
      if (err.status === 409) setExpiresAt(null);
    } finally {
      setBusy(false);
      refresh();
    }
  }

  const holdSelected = () =>
    run(async () => {
      holdingNow.current = new Set(selected);
      try {
        const res = await api.hold(eventId, selected);
        setExpiresAt(res.expiresAt);
        setSelected([]);
        checkoutKey.current = crypto.randomUUID();
      } finally {
        holdingNow.current = new Set();
      }
    });

  const bookHeld = () =>
    run(async () => {
      const key = checkoutKey.current ?? (checkoutKey.current = crypto.randomUUID());
      const seatIds = held.map((s) => s.id);
      let result: Booking;
      try {
        result = await api.book(eventId, seatIds, key);
      } catch (e) {
        // Network dropped before we heard back. The booking may or may not have gone through,
        // so retry with the SAME key: the server returns the original booking if it exists.
        if ((e as ApiError).status !== 0) throw e;
        result = await api.book(eventId, seatIds, key);
      }
      setBooking(result);
      setExpiresAt(null);
      checkoutKey.current = null;
    });

  const releaseHeld = () =>
    run(async () => {
      await api.release(eventId, held.map((s) => s.id));
      setExpiresAt(null);
    });

  const onExpire = useCallback(() => {
    setExpiresAt(null);
    setMessage({ kind: 'info', text: 'Your hold ran out and the seats were released. Select them again.' });
    refresh();
  }, [refresh]);

  if (loadError) return <p className="notice notice-error">{loadError}</p>;
  if (!event || !map) return <p className="muted">Loading seats</p>;

  const selectedSeats = selected.map((id) => seatsById.get(id)).filter((s): s is SeatView => Boolean(s));
  const panelSeats = held.length ? held : selectedSeats;
  const total = panelSeats.reduce((sum, s) => sum + s.priceCents, 0);

  return (
    <div className="event-layout">
      <section className="event-main">
        <Link to="/" className="back">
          All events
        </Link>
        <h1>{event.name}</h1>
        <p className="muted">
          {event.venue}, {formatDate(event.startsAt)} at {formatTime(event.startsAt)}
        </p>
        <p className={live ? 'live live-on' : 'live'} role="status">
          <span className="live-dot" aria-hidden="true" />
          {live ? 'Seats update live' : 'Reconnecting to live updates'}
        </p>
        <SeatMap
          rows={map.rows}
          selected={new Set(selected)}
          lockAvailable={held.length > 0}
          onSeatClick={onSeatClick}
        />
      </section>

      <aside className="panel" aria-live="polite">
        {booking ? (
          <div className="confirmation">
            <h2>You're booked</h2>
            <p className="big-seats">{booking.seats.map(seatLabel).join(', ')}</p>
            <p className="muted">
              {formatPrice(booking.totalCents)}, booking #{booking.id}
            </p>
            <p className="fine-print">A confirmation email is on its way.</p>
            <Link to="/bookings" className="button button-primary">
              See my bookings
            </Link>
            <button type="button" className="link-button" onClick={() => setBooking(null)}>
              Book more seats
            </button>
          </div>
        ) : (
          <>
            <h2>{held.length ? 'Your seats' : 'Your selection'}</h2>

            {held.length > 0 && expiresAt && (
              <HoldTimer expiresAt={expiresAt} totalSeconds={map.holdTtlSeconds} onExpire={onExpire} />
            )}

            {panelSeats.length === 0 ? (
              <p className="muted">
                {user ? `Tap open seats to select up to ${maxSeats}.` : 'Sign in, then tap open seats to select them.'}
              </p>
            ) : (
              <ul className="picked">
                {panelSeats.map((s) => (
                  <li key={s.id}>
                    <span>Seat {seatLabel(s)}</span>
                    <span>{formatPrice(s.priceCents)}</span>
                  </li>
                ))}
                <li className="picked-total">
                  <span>Total</span>
                  <span>{formatPrice(total)}</span>
                </li>
              </ul>
            )}

            {message && <p className={`notice notice-${message.kind}`}>{message.text}</p>}

            {held.length > 0 ? (
              <div className="actions">
                <button type="button" className="button button-primary" onClick={bookHeld} disabled={busy}>
                  {busy ? 'Booking' : `Book ${held.length} ${held.length === 1 ? 'seat' : 'seats'}`}
                </button>
                <button type="button" className="link-button" onClick={releaseHeld} disabled={busy}>
                  Release seats
                </button>
              </div>
            ) : (
              selected.length > 0 && (
                <div className="actions">
                  <button type="button" className="button button-primary" onClick={holdSelected} disabled={busy}>
                    {busy ? 'Holding' : `Hold ${selected.length} ${selected.length === 1 ? 'seat' : 'seats'}`}
                  </button>
                  <p className="fine-print">Held seats are yours for {Math.round(map.holdTtlSeconds / 60)} minutes.</p>
                </div>
              )
            )}
          </>
        )}
      </aside>
    </div>
  );
}
