import { Client } from '@stomp/stompjs';

export type PublicSeatStatus = 'AVAILABLE' | 'HELD' | 'BOOKED';

export interface SeatUpdate {
  eventId: number;
  changes: { seatId: number; status: PublicSeatStatus }[];
}

/**
 * Opens a STOMP-over-WebSocket connection and listens for seat changes on one event.
 * Reconnects automatically if the connection drops. Returns a function that disconnects.
 */
export function subscribeToSeats(
  eventId: number,
  onUpdate: (update: SeatUpdate) => void,
  onConnectionChange: (connected: boolean) => void,
): () => void {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const client = new Client({
    brokerURL: `${scheme}://${window.location.host}/ws`,
    reconnectDelay: 3000,
  });

  client.onConnect = () => {
    onConnectionChange(true);
    client.subscribe(`/topic/events/${eventId}/seats`, (message) => onUpdate(JSON.parse(message.body)));
  };
  client.onWebSocketClose = () => onConnectionChange(false);
  client.activate();

  return () => {
    void client.deactivate();
  };
}
