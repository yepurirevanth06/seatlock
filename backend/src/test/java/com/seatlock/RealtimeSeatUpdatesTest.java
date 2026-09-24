package com.seatlock;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.seatlock.realtime.SeatBroadcaster;
import com.seatlock.realtime.SeatUpdate;
import com.seatlock.realtime.SeatUpdate.PublicStatus;
import com.seatlock.seat.Seat;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * Runs the real server on a random port, connects a STOMP client exactly like the browser does,
 * and checks that holds, bookings, and hold expiry all reach watchers without polling.
 * Holds last 2 seconds here so the expiry test finishes quickly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.seed-demo-data=false", "app.holds.ttl-seconds=2", "app.outbox-poll-ms=200"})
class RealtimeSeatUpdatesTest extends IntegrationTestBase {

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired TestData data;

    private final BlockingQueue<SeatUpdate> received = new LinkedBlockingQueue<>();
    private StompSession session;

    @AfterEach
    void disconnect() {
        if (session != null && session.isConnected()) {
            session.disconnect();
        }
    }

    @Test
    void holdAndBookingArePushedToWatchers() throws Exception {
        Seat seat = data.eventWithSeats(1, 2, 500).get(0);
        watch(seat.getEventId());
        String token = register();

        ResponseEntity<String> hold = post("/api/events/" + seat.getEventId() + "/holds",
                Map.of("seatIds", List.of(seat.getId())), token);
        assertThat(hold.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(nextStatusOf(seat.getId())).isEqualTo(PublicStatus.HELD);

        ResponseEntity<String> booking = post("/api/bookings",
                Map.of("eventId", seat.getEventId(), "seatIds", List.of(seat.getId())), token);
        assertThat(booking.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(nextStatusOf(seat.getId())).isEqualTo(PublicStatus.BOOKED);
    }

    @Test
    void expiredHoldIsPushedAsAvailableWithoutAnyApiCall() throws Exception {
        Seat seat = data.eventWithSeats(1, 1, 0).get(0);
        watch(seat.getEventId());
        String token = register();

        post("/api/events/" + seat.getEventId() + "/holds", Map.of("seatIds", List.of(seat.getId())), token);
        assertThat(nextStatusOf(seat.getId())).isEqualTo(PublicStatus.HELD);

        // Nobody touches the API; Redis expires the key and the listener broadcasts it.
        assertThat(nextStatusOf(seat.getId())).isEqualTo(PublicStatus.AVAILABLE);
    }

    @Test
    void releasingAHoldIsPushedImmediately() throws Exception {
        Seat seat = data.eventWithSeats(1, 1, 0).get(0);
        watch(seat.getEventId());
        String token = register();
        Map<String, Object> body = Map.of("seatIds", List.of(seat.getId()));

        post("/api/events/" + seat.getEventId() + "/holds", body, token);
        assertThat(nextStatusOf(seat.getId())).isEqualTo(PublicStatus.HELD);

        post("/api/events/" + seat.getEventId() + "/holds/release", body, token);
        assertThat(nextStatusOf(seat.getId())).isEqualTo(PublicStatus.AVAILABLE);
    }

    private void watch(Long eventId) throws Exception {
        WebSocketStompClient stomp = new WebSocketStompClient(new StandardWebSocketClient());
        stomp.setMessageConverter(new MappingJackson2MessageConverter());
        session = stomp.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {})
                .get(10, TimeUnit.SECONDS);
        session.subscribe(SeatBroadcaster.topic(eventId), new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return SeatUpdate.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((SeatUpdate) payload);
            }
        });
        Thread.sleep(300); // give the broker a moment to register the subscription
    }

    private PublicStatus nextStatusOf(Long seatId) throws InterruptedException {
        SeatUpdate update = received.poll(10, TimeUnit.SECONDS);
        assertThat(update).as("expected a seat update within 10 seconds").isNotNull();
        return update.changes().stream()
                .filter(c -> c.seatId().equals(seatId))
                .map(SeatUpdate.SeatChange::status)
                .findFirst()
                .orElseThrow();
    }

    private String register() {
        Map<String, String> body = Map.of("email", "rt-" + UUID.randomUUID() + "@example.com",
                "password", "correct-horse", "displayName", "Realtime");
        JsonNode response = http.postForObject("/api/auth/register", body, JsonNode.class);
        return response.get("token").asText();
    }

    private ResponseEntity<String> post(String path, Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
