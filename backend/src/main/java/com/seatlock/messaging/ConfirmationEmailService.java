package com.seatlock.messaging;

import com.seatlock.booking.Booking;
import com.seatlock.booking.BookingRepository;
import com.seatlock.event.Event;
import com.seatlock.event.EventRepository;
import com.seatlock.seat.Seat;
import com.seatlock.seat.SeatRepository;
import com.seatlock.user.User;
import com.seatlock.user.UserRepository;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfirmationEmailService {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a z").withZone(ZoneId.of("America/New_York"));

    private final JdbcTemplate jdbc;
    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final EventRepository events;
    private final UserRepository users;
    private final JavaMailSender mail;
    private final String from;

    public ConfirmationEmailService(JdbcTemplate jdbc, BookingRepository bookings, SeatRepository seats,
                                    EventRepository events, UserRepository users, JavaMailSender mail,
                                    @Value("${app.mail-from}") String from) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.seats = seats;
        this.events = events;
        this.users = users;
        this.mail = mail;
        this.from = from;
    }

    /**
     * Sends the confirmation at most once per booking, even if RabbitMQ delivers the message
     * several times. Returns false when it was already sent.
     *
     * The dedup row and the send share one transaction: if sending throws, the row rolls back
     * and the retried message tries again. (If the mail server accepts the email but the commit
     * then fails, one duplicate email is possible. That trade-off is normal for email.)
     */
    @Transactional
    public boolean sendConfirmation(Long bookingId) {
        int claimed = jdbc.update(
                "INSERT INTO booking_notifications (booking_id) VALUES (?) ON CONFLICT DO NOTHING", bookingId);
        if (claimed == 0) {
            return false;
        }

        Booking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new IllegalStateException("Booking " + bookingId + " not found"));
        User user = users.findById(booking.getUserId()).orElseThrow();
        Event event = events.findById(booking.getEventId()).orElseThrow();
        List<Seat> booked = seats.findByBookingIdInOrderByRowLabelAscSeatNumberAsc(List.of(bookingId));
        String seatList = booked.stream().map(Seat::label).collect(Collectors.joining(", "));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("You're booked: " + event.getName() + " (" + seatList + ")");
        message.setText("""
                Hi %s,

                Your seats are confirmed.

                Event: %s
                Venue: %s
                When:  %s
                Seats: %s
                Total: $%.2f
                Booking #%d

                See you there,
                SeatLock
                """.formatted(user.getDisplayName(), event.getName(), event.getVenue(),
                WHEN.format(event.getStartsAt()), seatList, booking.getTotalCents() / 100.0, bookingId));
        mail.send(message);
        return true;
    }
}
