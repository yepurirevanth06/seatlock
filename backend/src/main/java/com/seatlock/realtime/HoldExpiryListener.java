package com.seatlock.realtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.listener.KeyExpirationEventMessageListener;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * When a 5-minute hold runs out, Redis deletes the key on its own. Nobody calls our API,
 * so without this listener other viewers would keep seeing the seat as held.
 *
 * Redis "keyspace notifications" publish an event whenever a key expires. This listener
 * turns on those notifications at startup (CONFIG SET notify-keyspace-events Ex),
 * receives the expired key name, and broadcasts the freed seat.
 */
@Component
public class HoldExpiryListener extends KeyExpirationEventMessageListener {

    // Matches keys written by SeatHoldService: hold:{event:42}:seat:7
    private static final Pattern HOLD_KEY = Pattern.compile("^hold:\\{event:(\\d+)\\}:seat:(\\d+)$");

    private final SeatBroadcaster broadcaster;

    public HoldExpiryListener(RedisMessageListenerContainer container, SeatBroadcaster broadcaster) {
        super(container);
        this.broadcaster = broadcaster;
        setKeyspaceNotificationsConfigParameter("Ex"); // E = keyevent channel, x = expired events
    }

    @Override
    protected void doHandleMessage(Message message) {
        String key = new String(message.getBody(), StandardCharsets.UTF_8);
        Matcher m = HOLD_KEY.matcher(key);
        if (m.matches()) {
            broadcaster.seatsChanged(Long.valueOf(m.group(1)), List.of(Long.valueOf(m.group(2))));
        }
    }
}
