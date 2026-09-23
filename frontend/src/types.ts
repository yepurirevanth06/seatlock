export type Role = 'USER' | 'ADMIN';

export interface User {
  id: number;
  email: string;
  displayName: string;
  role: Role;
}

export interface AuthResponse {
  token: string;
  user: User;
}

export interface EventSummary {
  id: number;
  name: string;
  venue: string;
  startsAt: string;
  totalSeats: number;
  availableSeats: number;
}

export type SeatStatus = 'AVAILABLE' | 'HELD' | 'HELD_BY_YOU' | 'BOOKED';

export interface SeatView {
  id: number;
  row: string;
  number: number;
  priceCents: number;
  status: SeatStatus;
}

export interface SeatRow {
  label: string;
  seats: SeatView[];
}

export interface SeatMap {
  eventId: number;
  rows: SeatRow[];
  holdTtlSeconds: number;
  maxSeatsPerHold: number;
}

export interface HoldResponse {
  seatIds: number[];
  expiresAt: string;
}

export interface Booking {
  id: number;
  eventId: number;
  eventName: string;
  venue: string;
  startsAt: string;
  seats: { id: number; row: string; number: number }[];
  totalCents: number;
  createdAt: string;
}
