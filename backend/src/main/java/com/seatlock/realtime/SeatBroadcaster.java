package com.seatlock.realtime;

import com.seatlock.hold.SeatHoldService;
import com.seatlock.realtime.SeatUpdate.PublicStatus;
import com.seatlock.realtime.SeatUpdate.SeatChange;
import com.seatlock.seat.Seat;
import com.seatlock.seat.SeatRepository;
import com.seatlock.seat.SeatStatus;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Pushes seat changes to every browser watching an event.
 *
 * Design choice: callers never say "seat 7 is now X". They say "seat 7 changed", and this
 * class reads the current truth from Postgres (booked?) and Redis (held?) before sending.
 * That keeps messages correct even when events race each other, for example a hold
 * expiring in the same instant the seat is booked.
 */
@Component
public class SeatBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(SeatBroadcaster.class);

    private final SimpMessagingTemplate messaging;
    private final SeatRepository seats;
    private final SeatHoldService holds;

    public SeatBroadcaster(SimpMessagingTemplate messaging, SeatRepository seats, SeatHoldService holds) {
        this.messaging = messaging;
        this.seats = seats;
        this.holds = holds;
    }

    public static String topic(Long eventId) {
        return "/topic/events/" + eventId + "/seats";
    }

    public void seatsChanged(Long eventId, List<Long> seatIds) {
        if (seatIds.isEmpty()) {
            return;
        }
        try {
            List<Seat> current = seats.findByEventIdAndIdIn(eventId, seatIds);
            Map<Long, Long> holders = holds.holders(eventId, seatIds);
            List<SeatChange> changes = current.stream()
                    .map(seat -> new SeatChange(seat.getId(), statusOf(seat, holders)))
                    .toList();
            messaging.convertAndSend(topic(eventId), new SeatUpdate(eventId, changes));
        } catch (RuntimeException e) {
            // Real-time updates are a convenience. Never fail a booking because a push failed;
            // clients also re-sync with a slow background refresh.
            log.warn("Could not broadcast seat changes for event {}: {}", eventId, e.getMessage());
        }
    }

    private static PublicStatus statusOf(Seat seat, Map<Long, Long> holders) {
        if (seat.getStatus() == SeatStatus.BOOKED) {
            return PublicStatus.BOOKED;
        }
        return holders.containsKey(seat.getId()) ? PublicStatus.HELD : PublicStatus.AVAILABLE;
    }
}
