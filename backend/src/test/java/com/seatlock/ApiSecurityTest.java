package com.seatlock;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.seatlock.seat.Seat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end through HTTP: auth, authorization rules, and the booking happy path. */
@AutoConfigureMockMvc
class ApiSecurityTest extends IntegrationTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestData data;

    @Test
    void registeredUserCanHoldAndBookThroughTheApi() throws Exception {
        String token = register();
        Seat seat = data.eventWithSeats(1, 2, 900).get(0);
        String body = json.writeValueAsString(Map.of("seatIds", List.of(seat.getId())));

        mvc.perform(post("/api/events/{id}/holds", seat.getEventId()).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatIds[0]").value(seat.getId()));

        String booking = json.writeValueAsString(Map.of("eventId", seat.getEventId(), "seatIds", List.of(seat.getId())));
        mvc.perform(post("/api/bookings").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(booking))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalCents").value(900));

        mvc.perform(get("/api/bookings/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void seatMapIsPublicButHoldingRequiresSignIn() throws Exception {
        Seat seat = data.eventWithSeats(1, 1, 0).get(0);

        mvc.perform(get("/api/events/{id}/seats", seat.getEventId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].seats[0].status").value("AVAILABLE"));

        mvc.perform(post("/api/events/{id}/holds", seat.getEventId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[" + seat.getId() + "]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regularUsersCannotCreateEvents() throws Exception {
        String token = register();
        String event = """
                {"name":"Sneaky","venue":"Nowhere","startsAt":"2099-01-01T18:00:00Z",
                 "rows":1,"seatsPerRow":1,"priceCents":0}
                """;
        mvc.perform(post("/api/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(event))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidRegistrationReturnsFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"short\",\"displayName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        register(email);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());
    }

    private String register() throws Exception {
        return register("user-" + UUID.randomUUID() + "@example.com");
    }

    private String register(String email) throws Exception {
        String response = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email, "password", "correct-horse", "displayName", "Test"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = json.readTree(response);
        return node.get("token").asText();
    }
}
