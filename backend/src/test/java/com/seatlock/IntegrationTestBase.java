package com.seatlock;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts real Postgres, Redis, RabbitMQ, and Mailpit (a fake mail server with an HTTP API)
 * in Docker once for the whole test run. Tests hit the same migrations, Lua scripts,
 * queues, and SMTP path as production, not mocks.
 *
 * The containers are started once in a static block (the "singleton container" pattern)
 * so Spring's cached test contexts keep pointing at live containers across test classes.
 */
@SpringBootTest(properties = {"app.seed-demo-data=false", "app.holds.ttl-seconds=300", "app.outbox-poll-ms=200"})
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static final GenericContainer<?> MAILPIT =
            new GenericContainer<>(DockerImageName.parse("axllent/mailpit:latest")).withExposedPorts(1025, 8025);

    static {
        POSTGRES.start();
        REDIS.start();
        RABBIT.start();
        MAILPIT.start();
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
    }

    /** Base URL of Mailpit's HTTP API, for checking that emails were delivered. */
    protected static String mailpitApi() {
        return "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1";
    }
}
