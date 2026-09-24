package com.seatlock.messaging;

import com.seatlock.messaging.OutboxRepository.OutboxEvent;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves committed outbox rows to RabbitMQ.
 *
 * Why not publish straight from the booking code? If the app crashed after the booking
 * committed but before publishing, the email would be lost forever. Publishing before commit
 * is worse: a rolled-back booking could still send an email. Writing the event in the same
 * transaction and relaying it afterwards gives "at least once" delivery with neither problem.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRepository outbox;
    private final RabbitTemplate rabbit;

    public OutboxRelay(OutboxRepository outbox, RabbitTemplate rabbit) {
        this.outbox = outbox;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${app.outbox-poll-ms:500}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outbox.lockUnpublished(BATCH_SIZE);
        if (batch.isEmpty()) {
            return;
        }
        try {
            // Publisher confirms: wait until RabbitMQ has actually accepted every message
            // before marking rows as published.
            rabbit.invoke(ops -> {
                for (OutboxEvent event : batch) {
                    ops.convertAndSend(MessagingConfig.EVENTS_EXCHANGE, routingKey(event), event.payload());
                }
                ops.waitForConfirmsOrDie(5_000);
                return null;
            });
        } catch (AmqpException e) {
            // Leave rows unpublished; the next run retries them. Some may have been delivered
            // already, which is fine because the consumer ignores duplicates.
            log.warn("Outbox publish failed, will retry {} events: {}", batch.size(), e.getMessage());
            return;
        }
        outbox.markPublished(batch.stream().map(OutboxEvent::id).toList());
    }

    private static String routingKey(OutboxEvent event) {
        return switch (event.eventType()) {
            case "BookingConfirmed" -> MessagingConfig.BOOKING_CONFIRMED;
            default -> throw new IllegalStateException("No route for outbox event type " + event.eventType());
        };
    }
}
