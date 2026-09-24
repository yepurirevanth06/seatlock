package com.seatlock.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology, declared automatically at startup.
 *
 *   seatlock.events (topic) --booking.confirmed--> booking.confirmed queue --> email consumer
 *                                                         | after 3 failed attempts
 *                                                         v
 *   seatlock.dlx (direct)  ------------------------> booking.confirmed.dlq (for inspection)
 */
@Configuration
public class MessagingConfig {

    public static final String EVENTS_EXCHANGE = "seatlock.events";
    public static final String BOOKING_CONFIRMED = "booking.confirmed";
    public static final String DEAD_LETTER_EXCHANGE = "seatlock.dlx";
    public static final String BOOKING_CONFIRMED_DLQ = "booking.confirmed.dlq";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE);
    }

    @Bean
    public Queue bookingConfirmedQueue() {
        return QueueBuilder.durable(BOOKING_CONFIRMED)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", BOOKING_CONFIRMED_DLQ)
                .build();
    }

    @Bean
    public Binding bookingConfirmedBinding() {
        return BindingBuilder.bind(bookingConfirmedQueue()).to(eventsExchange()).with(BOOKING_CONFIRMED);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE);
    }

    @Bean
    public Queue bookingConfirmedDeadLetterQueue() {
        return QueueBuilder.durable(BOOKING_CONFIRMED_DLQ).build();
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(bookingConfirmedDeadLetterQueue()).to(deadLetterExchange())
                .with(BOOKING_CONFIRMED_DLQ);
    }
}
