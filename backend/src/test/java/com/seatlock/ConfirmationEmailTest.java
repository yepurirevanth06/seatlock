package com.seatlock;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatlock.booking.BookingDtos.BookingResponse;
import com.seatlock.booking.BookingService;
import com.seatlock.hold.SeatHoldService;
import com.seatlock.messaging.ConfirmationEmailService;
import com.seatlock.messaging.OutboxRepository;
import com.seatlock.seat.Seat;
import com.seatlock.user.Role;
import com.seatlock.user.User;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Follows a booking through the whole async path:
 * booking transaction -> outbox row -> relay -> RabbitMQ -> consumer -> SMTP -> Mailpit inbox.
 */
class ConfirmationEmailTest extends IntegrationTestBase {

    @Autowired TestData data;
    @Autowired SeatHoldService holds;
    @Autowired BookingService bookingService;
    @Autowired ConfirmationEmailService emails;
    @Autowired OutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;

    private final RestTemplate mailpit = new RestTemplate();

    @Test
    void bookingProducesExactlyOneConfirmationEmail() throws Exception {
        Seat seat = data.eventWithSeats(1, 1, 2500).get(0);
        User alice = data.user(Role.USER);
        holds.hold(seat.getEventId(), List.of(seat.getId()), alice.getId());

        BookingResponse booking = bookingService.confirm(alice.getId(), seat.getEventId(), List.of(seat.getId()));

        // The outbox row commits with the booking and gets relayed within a moment.
        waitUntil(() -> countNotifications(booking.id()) == 1, "notification recorded");
        waitUntil(() -> inboxContains(alice.getEmail()), "email delivered to Mailpit");

        // Simulate RabbitMQ redelivering the same message: nothing new is sent.
        assertThat(emails.sendConfirmation(booking.id())).isFalse();
        assertThat(countNotifications(booking.id())).isEqualTo(1);
    }

    @Test
    void outboxEventsAreMarkedPublished() throws Exception {
        Seat seat = data.eventWithSeats(1, 1, 0).get(0);
        User alice = data.user(Role.USER);
        holds.hold(seat.getEventId(), List.of(seat.getId()), alice.getId());
        bookingService.confirm(alice.getId(), seat.getEventId(), List.of(seat.getId()));

        waitUntil(() -> outbox.countUnpublished() == 0, "outbox drained");
    }

    private int countNotifications(Long bookingId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM booking_notifications WHERE booking_id = ?",
                Integer.class, bookingId);
        return n == null ? 0 : n;
    }

    private boolean inboxContains(String address) {
        String inbox = mailpit.getForObject(mailpitApi() + "/messages?limit=500", String.class);
        return inbox != null && inbox.contains(address);
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for: " + what);
    }
}
