package com.seatlock.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Consumes booking.confirmed messages. A thrown exception triggers up to 3 attempts with
 * backoff (see application.yml); after that the message goes to the dead-letter queue.
 */
@Component
public class BookingConfirmedListener {

    private static final Logger log = LoggerFactory.getLogger(BookingConfirmedListener.class);

    private final ConfirmationEmailService emails;
    private final ObjectMapper json;

    public BookingConfirmedListener(ConfirmationEmailService emails, ObjectMapper json) {
        this.emails = emails;
        this.json = json;
    }

    @RabbitListener(queues = MessagingConfig.BOOKING_CONFIRMED)
    public void onBookingConfirmed(String payload) throws Exception {
        JsonNode node = json.readTree(payload);
        long bookingId = node.get("bookingId").asLong();
        boolean sent = emails.sendConfirmation(bookingId);
        log.info("Booking {} confirmation {}", bookingId, sent ? "sent" : "already sent, skipped duplicate");
    }
}
