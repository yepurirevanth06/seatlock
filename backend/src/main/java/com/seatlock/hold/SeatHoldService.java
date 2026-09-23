package com.seatlock.hold;

import com.seatlock.common.BadRequestException;
import com.seatlock.common.NotFoundException;
import com.seatlock.common.SeatConflictException;
import com.seatlock.config.AppProperties;
import com.seatlock.seat.Seat;
import com.seatlock.seat.SeatRepository;
import com.seatlock.seat.SeatStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Temporary seat holds stored in Redis with an expiry.
 *
 * Key format: hold:{event:42}:seat:7  ->  value = user id, TTL = hold duration.
 * The {event:42} part is a Redis Cluster "hash tag" so every key for one event lands
 * on the same node, which multi-key Lua scripts require.
 *
 * Holds are a fast, friendly first line of defense (people see seats disappear while
 * they shop). They are NOT the final guarantee; the database lock in BookingWriter is.
 */
@Service
public class SeatHoldService {

    /**
     * All-or-nothing hold. Redis runs a Lua script as one atomic step, so no other
     * command can sneak in between "check the seats are free" and "take them".
     * Returns the 1-based positions of seats held by someone else (empty = success).
     */
    private static final String HOLD_LUA = """
            local conflicts = {}
            for i, key in ipairs(KEYS) do
              local owner = redis.call('GET', key)
              if owner and owner ~= ARGV[1] then
                table.insert(conflicts, i)
              end
            end
            if #conflicts > 0 then
              return conflicts
            end
            for _, key in ipairs(KEYS) do
              redis.call('SET', key, ARGV[1], 'PX', ARGV[2])
            end
            return conflicts
            """;

    /** Deletes only the keys this user owns, so nobody can release someone else's hold. */
    private static final String RELEASE_LUA = """
            local released = 0
            for _, key in ipairs(KEYS) do
              if redis.call('GET', key) == ARGV[1] then
                redis.call('DEL', key)
                released = released + 1
              end
            end
            return released
            """;

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> HOLD_SCRIPT = new DefaultRedisScript<>(HOLD_LUA, List.class);
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(RELEASE_LUA, Long.class);

    private final StringRedisTemplate redis;
    private final SeatRepository seats;
    private final Duration ttl;
    private final int maxSeats;

    public SeatHoldService(StringRedisTemplate redis, SeatRepository seats, AppProperties props) {
        this.redis = redis;
        this.seats = seats;
        this.ttl = Duration.ofSeconds(props.holds().ttlSeconds());
        this.maxSeats = props.holds().maxSeats();
    }

    public record HoldResult(List<Long> seatIds, Instant expiresAt) {}

    public HoldResult hold(Long eventId, List<Long> requestedSeatIds, Long userId) {
        List<Long> seatIds = normalize(requestedSeatIds);

        List<Seat> found = seats.findByEventIdAndIdIn(eventId, seatIds);
        if (found.size() != seatIds.size()) {
            throw new NotFoundException("One or more seats do not exist for this event");
        }
        List<Long> alreadyBooked = found.stream()
                .filter(s -> s.getStatus() == SeatStatus.BOOKED)
                .map(Seat::getId)
                .toList();
        if (!alreadyBooked.isEmpty()) {
            throw new SeatConflictException("Some of these seats are already booked", alreadyBooked);
        }

        List<?> conflicts = redis.execute(HOLD_SCRIPT, keys(eventId, seatIds),
                userId.toString(), String.valueOf(ttl.toMillis()));
        if (conflicts != null && !conflicts.isEmpty()) {
            List<Long> taken = conflicts.stream()
                    .map(position -> seatIds.get(((Number) position).intValue() - 1))
                    .toList();
            throw new SeatConflictException("Someone else is holding some of these seats", taken);
        }
        return new HoldResult(seatIds, Instant.now().plus(ttl));
    }

    public int release(Long eventId, List<Long> requestedSeatIds, Long userId) {
        List<Long> seatIds = normalize(requestedSeatIds);
        Long released = redis.execute(RELEASE_SCRIPT, keys(eventId, seatIds), userId.toString());
        return released == null ? 0 : released.intValue();
    }

    /** Seat id to holder user id, for seats that currently have a hold. */
    public Map<Long, Long> holders(Long eventId, List<Long> seatIds) {
        Map<Long, Long> result = new HashMap<>();
        if (seatIds.isEmpty()) {
            return result;
        }
        List<String> owners = redis.opsForValue().multiGet(keys(eventId, seatIds));
        if (owners == null) {
            return result;
        }
        for (int i = 0; i < seatIds.size(); i++) {
            String owner = owners.get(i);
            if (owner != null) {
                result.put(seatIds.get(i), Long.valueOf(owner));
            }
        }
        return result;
    }

    /** Seats from the list that this user does not currently hold (expired, never held, or someone else's). */
    public List<Long> seatsNotHeldBy(Long eventId, List<Long> seatIds, Long userId) {
        Map<Long, Long> owners = holders(eventId, seatIds);
        List<Long> missing = new ArrayList<>();
        for (Long seatId : seatIds) {
            if (!Objects.equals(owners.get(seatId), userId)) {
                missing.add(seatId);
            }
        }
        return missing;
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    public int maxSeats() {
        return maxSeats;
    }

    public List<Long> normalize(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            throw new BadRequestException("Choose at least one seat");
        }
        List<Long> unique = seatIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (unique.size() > maxSeats) {
            throw new BadRequestException("You can hold up to " + maxSeats + " seats at a time");
        }
        return unique;
    }

    static String key(Long eventId, Long seatId) {
        return "hold:{event:" + eventId + "}:seat:" + seatId;
    }

    private static List<String> keys(Long eventId, List<Long> seatIds) {
        return seatIds.stream().map(id -> key(eventId, id)).toList();
    }
}
