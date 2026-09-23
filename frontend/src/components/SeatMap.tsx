import type { SeatRow, SeatView } from '../types';
import { formatPrice, seatLabel } from '../format';

interface Props {
  rows: SeatRow[];
  selected: Set<number>;
  lockAvailable: boolean; // true while the viewer already holds seats
  onSeatClick: (seat: SeatView) => void;
}

const STATUS_TEXT: Record<SeatView['status'], string> = {
  AVAILABLE: 'available',
  HELD: 'held by someone else',
  HELD_BY_YOU: 'held for you',
  BOOKED: 'booked',
};

export default function SeatMap({ rows, selected, lockAvailable, onSeatClick }: Props) {
  const widest = Math.max(0, ...rows.map((r) => r.seats.length));

  return (
    <div className="seatmap">
      <div className="seatmap-scroll">
        <div className="seatmap-inner" style={{ ['--cols' as string]: widest }}>
          <svg className="stage" viewBox="0 0 400 44" preserveAspectRatio="none" aria-hidden="true">
            <path d="M8 40 Q200 -8 392 40" />
          </svg>
          <p className="stage-label">Stage</p>

          {rows.map((row) => (
            <div className="seat-row" key={row.label}>
              <span className="row-label" aria-hidden="true">
                {row.label}
              </span>
              <div className="seat-row-seats" role="group" aria-label={`Row ${row.label}`}>
                {row.seats.map((seat) => {
                  const isSelected = selected.has(seat.id);
                  const clickable =
                    (seat.status === 'AVAILABLE' && !lockAvailable) || seat.status === 'HELD_BY_YOU';
                  const cls = ['seat', `seat-${seat.status.toLowerCase().replace(/_/g, '-')}`];
                  if (isSelected) cls.push('seat-selected');
                  return (
                    <button
                      key={seat.id}
                      type="button"
                      className={cls.join(' ')}
                      disabled={!clickable}
                      aria-pressed={seat.status === 'AVAILABLE' ? isSelected : undefined}
                      aria-label={`Seat ${seatLabel(seat)}, ${formatPrice(seat.priceCents)}, ${
                        isSelected ? 'selected' : STATUS_TEXT[seat.status]
                      }`}
                      title={`${seatLabel(seat)}, ${formatPrice(seat.priceCents)}`}
                      onClick={() => onSeatClick(seat)}
                    />
                  );
                })}
              </div>
              <span className="row-label" aria-hidden="true">
                {row.label}
              </span>
            </div>
          ))}
        </div>
      </div>

      <ul className="legend" aria-label="Seat key">
        <li>
          <span className="seat seat-available" aria-hidden="true" /> Open
        </li>
        <li>
          <span className="seat seat-available seat-selected" aria-hidden="true" /> Selected
        </li>
        <li>
          <span className="seat seat-held-by-you" aria-hidden="true" /> Held for you
        </li>
        <li>
          <span className="seat seat-held" aria-hidden="true" /> Someone is checking out
        </li>
        <li>
          <span className="seat seat-booked" aria-hidden="true" /> Booked
        </li>
      </ul>
    </div>
  );
}
