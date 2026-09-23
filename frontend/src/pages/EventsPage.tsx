import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, ApiError } from '../api';
import { formatTime } from '../format';
import type { EventSummary } from '../types';

export default function EventsPage() {
  const [events, setEvents] = useState<EventSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api
      .events()
      .then(setEvents)
      .catch((e: ApiError) => setError(e.message));
  }, []);

  return (
    <>
      <section className="intro">
        <h1>Pick your seat before someone else does.</h1>
        <p>
          Choose seats on the map and they are held for you for five minutes while you check out. Nobody else can take
          them in that time.
        </p>
      </section>

      {error && <p className="notice notice-error">{error}</p>}
      {!events && !error && <p className="muted">Loading events</p>}
      {events?.length === 0 && <p className="notice">No events are scheduled yet.</p>}

      {events && events.length > 0 && (
        <ol className="program">
          {events.map((e) => {
            const date = new Date(e.startsAt);
            const soldOut = e.availableSeats === 0;
            return (
              <li key={e.id} className="program-item">
                <time className="date-block" dateTime={e.startsAt}>
                  <span className="date-month">{date.toLocaleString('en-US', { month: 'short' })}</span>
                  <span className="date-day">{date.getDate()}</span>
                </time>
                <div className="program-body">
                  <h2>{e.name}</h2>
                  <p className="muted">
                    {e.venue}, {formatTime(e.startsAt)}
                  </p>
                </div>
                <div className="program-action">
                  <span className={soldOut ? 'availability sold-out' : 'availability'}>
                    {soldOut ? 'Sold out' : `${e.availableSeats} of ${e.totalSeats} open`}
                  </span>
                  <Link to={`/events/${e.id}`} className="button">
                    {soldOut ? 'View seats' : 'Choose seats'}
                  </Link>
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </>
  );
}
