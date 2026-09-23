import type { AuthResponse, Booking, EventSummary, HoldResponse, SeatMap, User } from './types';

const TOKEN_KEY = 'seatlock.token';

export const tokenStore = {
  get: () => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

/** Error shape returned by the backend's GlobalExceptionHandler. */
export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly seatIds: number[] = [],
    public readonly fieldErrors: Record<string, string> = {},
  ) {
    super(message);
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body) headers.set('Content-Type', 'application/json');
  const token = tokenStore.get();
  if (token) headers.set('Authorization', `Bearer ${token}`);

  let response: Response;
  try {
    response = await fetch(`/api${path}`, { ...init, headers });
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Check that the backend is running.');
  }

  const text = await response.text();
  const body = text ? JSON.parse(text) : undefined;

  if (!response.ok) {
    if (response.status === 401) tokenStore.clear();
    throw new ApiError(
      response.status,
      body?.message ?? (response.status === 401 ? 'Sign in to continue.' : `Request failed (${response.status})`),
      body?.seatIds ?? [],
      body?.fieldErrors ?? {},
    );
  }
  return body as T;
}

const post = <T>(path: string, data: unknown) => request<T>(path, { method: 'POST', body: JSON.stringify(data) });

export const api = {
  register: (email: string, password: string, displayName: string) =>
    post<AuthResponse>('/auth/register', { email, password, displayName }),
  login: (email: string, password: string) => post<AuthResponse>('/auth/login', { email, password }),
  me: () => request<User>('/auth/me'),

  events: () => request<EventSummary[]>('/events'),
  event: (id: number) => request<EventSummary>(`/events/${id}`),
  seatMap: (id: number) => request<SeatMap>(`/events/${id}/seats`),
  hold: (eventId: number, seatIds: number[]) => post<HoldResponse>(`/events/${eventId}/holds`, { seatIds }),
  release: (eventId: number, seatIds: number[]) =>
    post<{ released: number }>(`/events/${eventId}/holds/release`, { seatIds }),

  book: (eventId: number, seatIds: number[]) => post<Booking>('/bookings', { eventId, seatIds }),
  myBookings: () => request<Booking[]>('/bookings/me'),
};
