package com.seatlock;

import com.seatlock.event.EventDtos.CreateEventRequest;
import com.seatlock.event.EventDtos.EventSummary;
import com.seatlock.event.EventService;
import com.seatlock.seat.Seat;
import com.seatlock.seat.SeatRepository;
import com.seatlock.user.Role;
import com.seatlock.user.User;
import com.seatlock.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Small helpers so each test builds its own isolated event and users. */
@Component
public class TestData {

    private final UserRepository users;
    private final EventService events;
    private final SeatRepository seats;

    public TestData(UserRepository users, EventService events, SeatRepository seats) {
        this.users = users;
        this.events = events;
        this.seats = seats;
    }

    public User user(Role role) {
        String email = "test-" + UUID.randomUUID() + "@example.com";
        // Password hash is irrelevant for service-level tests; skip bcrypt to keep setup fast.
        return users.save(new User(email, "not-a-real-hash", "Test User", role));
    }

    public List<User> users(int count) {
        List<User> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            created.add(user(Role.USER));
        }
        return created;
    }

    public Long eventIdOfSeat(Long seatId) {
        return seats.findById(seatId).orElseThrow().getEventId();
    }

    /** Creates an event and returns its seats ordered by row then number. */
    public List<Seat> eventWithSeats(int rows, int seatsPerRow, int priceCents) {
        User admin = user(Role.ADMIN);
        EventSummary event = events.create(new CreateEventRequest("Test event " + UUID.randomUUID(),
                "Test venue", Instant.now().plus(Duration.ofDays(7)), rows, seatsPerRow, priceCents), admin.getId());
        return seats.findByEventIdOrderByRowLabelAscSeatNumberAsc(event.id());
    }
}
