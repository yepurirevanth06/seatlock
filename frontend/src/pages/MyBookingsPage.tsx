import { useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import { api, ApiError } from '../api';
import { useAuth } from '../auth';
import { formatDate, formatPrice, formatTime, seatLabel } from '../format';
import type { Booking } from '../types';

export default function MyBookingsPage() {
  const { user, loading } = useAuth();
  const [bookings, setBookings] = useState<Booking[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!user) return;
    api
      .myBookings()
      .then(setBookings)
      .catch((e: ApiError) => setError(e.message));
  }, [user]);

  if (!loading && !user) return <Navigate to="/login" state={{ from: '/bookings' }} replace />;

  return (
    <section>
      <h1>My bookings</h1>
      {error && <p className="notice notice-error">{error}</p>}
      {!bookings && !error && <p className="muted">Loading bookings</p>}
      {bookings?.length === 0 && (
        <p className="notice">
          You have not booked any seats yet. <Link to="/">Browse events</Link>
        </p>
      )}
      <ul className="tickets">
        {bookings?.map((b) => (
          <li key={b.id} className="ticket">
            <div className="ticket-main">
              <h2>{b.eventName}</h2>
              <p className="muted">
                {b.venue}, {formatDate(b.startsAt)} at {formatTime(b.startsAt)}
              </p>
            </div>
            <div className="ticket-stub">
              <span className="ticket-seats">{b.seats.map(seatLabel).join(', ')}</span>
              <span className="muted">
                {formatPrice(b.totalCents)}, booking #{b.id}
              </span>
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}
