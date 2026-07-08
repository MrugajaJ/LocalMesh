package dev.localmesh.controller.redis;

import com.google.gson.Gson;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Publishes structured events to the Redis pub/sub channel "localmesh:events".
 * The dashboard SSE stream subscribes to this channel.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final String EVENTS_CHANNEL = "localmesh:events";

    private final RedisCommands<String, String> redis;
    private final Gson gson = new Gson();

    public EventPublisher(RedisCommands<String, String> redis) {
        this.redis = redis;
    }

    public void publishInterceptActive(String interceptId, String serviceName, String sessionId) {
        publish(Map.of(
                "type", "INTERCEPT_ACTIVE",
                "interceptId", interceptId,
                "serviceName", serviceName,
                "sessionId", sessionId,
                "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }

    public void publishInterceptTornDown(String interceptName, String serviceName) {
        publish(Map.of(
                "type", "INTERCEPT_TORN_DOWN",
                "interceptName", interceptName,
                "serviceName", serviceName,
                "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }

    public void publishSessionExpired(String sessionId) {
        publish(Map.of(
                "type", "SESSION_EXPIRED",
                "sessionId", sessionId,
                "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }

    public void publishMetrics(String interceptId, double reqPerSecond, double p99Ms, double errorPct) {
        publish(Map.of(
                "type", "METRICS_UPDATE",
                "interceptId", interceptId,
                "reqPerSecond", String.valueOf(reqPerSecond),
                "p99Ms", String.valueOf(p99Ms),
                "errorPct", String.valueOf(errorPct),
                "timestamp", String.valueOf(System.currentTimeMillis())
        ));
    }

    private void publish(Map<String, String> payload) {
        try {
            String json = gson.toJson(payload);
            redis.publish(EVENTS_CHANNEL, json);
            log.debug("Published to {}: {}", EVENTS_CHANNEL, json);
        } catch (Exception e) {
            log.error("Failed to publish Redis event", e);
        }
    }
}
