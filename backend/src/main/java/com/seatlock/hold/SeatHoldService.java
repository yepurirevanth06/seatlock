package com.seatlock.hold;

import com.seatlock.common.BadRequestException;
import com.seatlock.common.HoldLimitException;
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
 * Per-user set: holds-by-user:{event:42}:user:9  ->  seat ids this user holds (for the cap).
 * The {event:42} part is a Redis Cluster "hash tag" so every key for one event lands
 * on the same node, which multi-key Lua scripts require.
 *
 * Holds are a fast, friendly first line of defense (people see seats disappear while
 * they shop). They are NOT the final guarantee; the database lock in BookingWriter is.
 */
@Service
public class SeatHoldService {

    /**
     * All-or-nothing hold with a per-user cap. Redis runs a Lua script as one atomic step, so no
     * other command can sneak in between "check the seats are free and the user is under the cap"
     * and "take them". Two simultaneous requests from one user therefore cannot both pass the cap.
     *
     * KEYS = the seat hold keys, then this user's set of held seat ids (last).
     * ARGV = user id, ttl ms, max seats per user, seat key prefix, then the seat ids.
     *
     * Returns {0} on success, {1, positions...} when seats are held by someone else,
     * or {2, currentlyHeld} when this would put the user over the cap.
     */
    private static final String HOLD_LUA = """
            local n = #KEYS - 1
            local userSet = KEYS[#KEYS]
            local user = ARGV[1]

            local conflicts = {1}
            local newSeats = 0
            for i = 1, n do
              local owner = redis.call('GET', KEYS[i])
              if owner and owner ~= user then
                table.insert(conflicts, i)
              elseif not owner then
                newSeats = newSeats + 1
              end
            end
            if #conflicts > 1 then
              return conflicts
            end

            -- Lazy cleanup: forget seats whose hold has expired or was released.
            for _, seatId in ipairs(redis.call('SMEMBERS', userSet)) do
              if redis.call('GET', ARGV[4] .. seatId) ~= user then
                redis.call('SREM', userSet, seatId)
              end
            end

            local current = redis.call('SCARD', userSet)
            if current + newSeats > tonumber(ARGV[3]) then
              return {2, current}
            end

            for i = 1, n do
              redis.call('SET', KEYS[i], user, 'PX', ARGV[2])
              redis.call('SADD', userSet, ARGV[4 + i])
            end
            redis.call('PEXPIRE', userSet, ARGV[2])
            return {0}
            """;

    /**
     * Deletes only the seat keys this user owns, so nobody can release someone else's hold,
     * and removes the seats from the user's set. KEYS = seat keys then the user set;
     * ARGV = user id, then the seat ids.
     */
    private static final String RELEASE_LUA = """
            local userSet = KEYS[#KEYS]
            local released = 0
            for i = 1, #KEYS - 1 do
              if redis.call('GET', KEYS[i]) == ARGV[1] then
                redis.call('DEL', KEYS[i])
                released = released + 1
              end
              redis.call('SREM', userSet, ARGV[i + 1])
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

        List<String> scriptKeys = new ArrayList<>(keys(eventId, seatIds));
        scriptKeys.add(userHoldsKey(eventId, userId));
        List<String> args = new ArrayList<>(List.of(userId.toString(), String.valueOf(ttl.toMillis()),
                String.valueOf(maxSeats), seatKeyPrefix(eventId)));
        seatIds.forEach(id -> args.add(id.toString()));

        List<?> result = redis.execute(HOLD_SCRIPT, scriptKeys, args.toArray());
        long code = result == null || result.isEmpty() ? -1 : ((Number) result.get(0)).longValue();
        if (code == 1) {
            List<Long> taken = result.subList(1, result.size()).stream()
                    .map(position -> seatIds.get(((Number) position).intValue() - 1))
                    .toList();
            throw new SeatConflictException("Someone else is holding some of these seats", taken);
        }
        if (code == 2) {
            throw new HoldLimitException(((Number) result.get(1)).longValue(), maxSeats);
        }
        if (code != 0) {
            throw new IllegalStateException("Unexpected result from hold script: " + result);
        }
        return new HoldResult(seatIds, Instant.now().plus(ttl));
    }

    public int release(Long eventId, List<Long> requestedSeatIds, Long userId) {
        List<Long> seatIds = normalize(requestedSeatIds);
        List<String> scriptKeys = new ArrayList<>(keys(eventId, seatIds));
        scriptKeys.add(userHoldsKey(eventId, userId));
        List<String> args = new ArrayList<>(List.of(userId.toString()));
        seatIds.forEach(id -> args.add(id.toString()));
        Long released = redis.execute(RELEASE_SCRIPT, scriptKeys, args.toArray());
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
        return seatKeyPrefix(eventId) + seatId;
    }

    static String seatKeyPrefix(Long eventId) {
        return "hold:{event:" + eventId + "}:seat:";
    }

    /** Same {event:N} hash tag as the seat keys, so one Lua script can touch both in Redis Cluster. */
    static String userHoldsKey(Long eventId, Long userId) {
        return "holds-by-user:{event:" + eventId + "}:user:" + userId;
    }

    private static List<String> keys(Long eventId, List<Long> seatIds) {
        return seatIds.stream().map(id -> key(eventId, id)).toList();
    }
}
