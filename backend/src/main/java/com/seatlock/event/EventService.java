package com.seatlock.event;

import com.seatlock.common.NotFoundException;
import com.seatlock.event.EventDtos.CreateEventRequest;
import com.seatlock.event.EventDtos.EventSummary;
import com.seatlock.event.EventDtos.SeatMapResponse;
import com.seatlock.event.EventDtos.SeatRow;
import com.seatlock.event.EventDtos.SeatView;
import com.seatlock.event.EventDtos.ViewerSeatStatus;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.seat.Seat;
import com.seatlock.seat.SeatCounts;
import com.seatlock.seat.SeatRepository;
import com.seatlock.seat.SeatStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EventService {

    private static final String ROW_LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

    private final EventRepository events;
    private final SeatRepository seats;
    private final SeatHoldService holds;

    public EventService(EventRepository events, SeatRepository seats, SeatHoldService holds) {
        this.events = events;
        this.seats = seats;
        this.holds = holds;
    }

    /** Creates the event and its full grid of seats in one transaction. */
    @Transactional
    public EventSummary create(CreateEventRequest request, Long adminId) {
        Event event = events.save(new Event(request.name().trim(), request.venue().trim(),
                request.startsAt(), adminId));

        List<Seat> grid = new ArrayList<>(request.rows() * request.seatsPerRow());
        for (int r = 0; r < request.rows(); r++) {
            String rowLabel = String.valueOf(ROW_LETTERS.charAt(r));
            for (int n = 1; n <= request.seatsPerRow(); n++) {
                grid.add(new Seat(event.getId(), rowLabel, n, request.priceCents()));
            }
        }
        seats.saveAll(grid);

        long total = grid.size();
        return toSummary(event, total, total);
    }

    @Transactional(readOnly = true)
    public List<EventSummary> list() {
        List<Event> all = events.findAllByOrderByStartsAtAsc();
        if (all.isEmpty()) {
            return List.of();
        }
        Map<Long, SeatCounts> counts = seats.countsByEvent(all.stream().map(Event::getId).toList())
                .stream()
                .collect(Collectors.toMap(SeatCounts::getEventId, Function.identity()));
        return all.stream().map(e -> {
            SeatCounts c = counts.get(e.getId());
            return toSummary(e, c == null ? 0 : c.getTotal(), c == null ? 0 : c.getAvailable());
        }).toList();
    }

    @Transactional(readOnly = true)
    public EventSummary get(Long eventId) {
        Event event = require(eventId);
        long total = seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId).size();
        long available = seats.countByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
        return toSummary(event, total, available);
    }

    /**
     * Merges durable state (Postgres) with temporary holds (Redis).
     * A booked seat always shows as BOOKED, even if a stale hold key is still around.
     */
    @Transactional(readOnly = true)
    public SeatMapResponse seatMap(Long eventId, Long viewerId) {
        require(eventId);
        List<Seat> all = seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(eventId);
        Map<Long, Long> holders = holds.holders(eventId, all.stream().map(Seat::getId).toList());

        Map<String, List<SeatView>> byRow = new LinkedHashMap<>();
        for (Seat seat : all) {
            ViewerSeatStatus status;
            Long holder = holders.get(seat.getId());
            if (seat.getStatus() == SeatStatus.BOOKED) {
                status = ViewerSeatStatus.BOOKED;
            } else if (holder == null) {
                status = ViewerSeatStatus.AVAILABLE;
            } else if (Objects.equals(holder, viewerId)) {
                status = ViewerSeatStatus.HELD_BY_YOU;
            } else {
                status = ViewerSeatStatus.HELD;
            }
            byRow.computeIfAbsent(seat.getRowLabel(), k -> new ArrayList<>())
                    .add(new SeatView(seat.getId(), seat.getRowLabel(), seat.getSeatNumber(),
                            seat.getPriceCents(), status));
        }
        List<SeatRow> rows = byRow.entrySet().stream()
                .map(e -> new SeatRow(e.getKey(), e.getValue()))
                .toList();
        return new SeatMapResponse(eventId, rows, holds.ttlSeconds(), holds.maxSeats());
    }

    Event require(Long eventId) {
        return events.findById(eventId).orElseThrow(() -> new NotFoundException("Event not found"));
    }

    private static EventSummary toSummary(Event e, long total, long available) {
        return new EventSummary(e.getId(), e.getName(), e.getVenue(), e.getStartsAt(), total, available);
    }
}
