package com.seatlock.config;

import com.seatlock.event.EventDtos.CreateEventRequest;
import com.seatlock.event.EventService;
import com.seatlock.user.Role;
import com.seatlock.user.User;
import com.seatlock.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates demo accounts and events on an empty database so the app is usable immediately. */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final AppProperties props;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EventService events;

    public DataSeeder(AppProperties props, UserRepository users, PasswordEncoder passwordEncoder,
                      EventService events) {
        this.props = props;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.events = events;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seedDemoData() || users.count() > 0) {
            return;
        }
        User admin = users.save(new User("admin@seatlock.dev", passwordEncoder.encode("admin12345"),
                "Events Office", Role.ADMIN));
        users.save(new User("student@seatlock.dev", passwordEncoder.encode("student12345"),
                "Demo Student", Role.USER));

        Instant today = Instant.now().truncatedTo(ChronoUnit.HOURS);
        events.create(new CreateEventRequest("Fall Career Panel: Software Engineering",
                "Johnson Center Cinema", today.plus(Duration.ofDays(9)), 8, 16, 0), admin.getId());
        events.create(new CreateEventRequest("International Night Showcase",
                "Center for the Arts Concert Hall", today.plus(Duration.ofDays(16)), 12, 22, 1500), admin.getId());
        events.create(new CreateEventRequest("Hackathon Kickoff and Demo Night",
                "Harris Theatre", today.plus(Duration.ofDays(23)), 10, 18, 500), admin.getId());

        log.info("Seeded demo data: admin@seatlock.dev / admin12345, student@seatlock.dev / student12345");
    }
}
