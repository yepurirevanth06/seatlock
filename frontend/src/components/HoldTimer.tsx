import { useEffect, useState } from 'react';

interface Props {
  expiresAt: string;
  totalSeconds: number;
  onExpire: () => void;
}

/** Counts down the Redis hold. The server is the source of truth; this is only a display. */
export default function HoldTimer({ expiresAt, totalSeconds, onExpire }: Props) {
  const deadline = new Date(expiresAt).getTime();
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, []);

  const remaining = Math.max(0, Math.round((deadline - now) / 1000));

  useEffect(() => {
    if (remaining === 0) onExpire();
  }, [remaining, onExpire]);

  const minutes = Math.floor(remaining / 60);
  const seconds = String(remaining % 60).padStart(2, '0');
  const fraction = totalSeconds > 0 ? remaining / totalSeconds : 0;

  return (
    <div className={remaining <= 60 ? 'timer timer-urgent' : 'timer'} role="timer" aria-live="off">
      <div className="timer-text">
        <span>Held for you</span>
        <strong>
          {minutes}:{seconds}
        </strong>
      </div>
      <div className="timer-track" aria-hidden="true">
        <div className="timer-fill" style={{ transform: `scaleX(${fraction})` }} />
      </div>
    </div>
  );
}
