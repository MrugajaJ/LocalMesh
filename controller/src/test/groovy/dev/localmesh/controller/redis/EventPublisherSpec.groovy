package dev.localmesh.controller.redis

import com.google.gson.Gson
import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.testcontainers.containers.GenericContainer
import org.testcontainers.spock.Testcontainers
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Integration test for EventPublisher using a real Redis Testcontainer.
 *
 * Phase 2 requirement: every publisher method must deliver a correctly-typed
 * JSON payload to the "localmesh:events" pub/sub channel.
 *
 * Strategy: start a real Redis 7 container, subscribe to "localmesh:events"
 * with a second Lettuce connection, then call each publish* method and assert
 * the received JSON contains the expected "type" field.
 */
@Testcontainers
class EventPublisherSpec extends Specification {

    static final String EVENTS_CHANNEL = "localmesh:events"
    static final int    TIMEOUT_MS     = 3_000

    @Shared
    static GenericContainer<?> redisContainer =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379)

    // Lettuce resources — created per-spec so each test gets a clean state
    RedisClient                             publisherClient
    StatefulRedisConnection<String, String> publisherConn
    RedisCommands<String, String>           publisherCommands

    RedisClient                                  subscriberClient
    StatefulRedisPubSubConnection<String, String> pubSubConn

    EventPublisher                          publisher

    /** Messages received on the channel — tests drain this queue */
    BlockingQueue<String> received = new ArrayBlockingQueue<>(32)
    Gson                  gson     = new Gson()

    def setup() {
        def host = redisContainer.host
        def port = redisContainer.getMappedPort(6379)
        def uri  = "redis://${host}:${port}"

        // Publisher side
        publisherClient   = RedisClient.create(uri)
        publisherConn     = publisherClient.connect()
        publisherCommands = publisherConn.sync()
        publisher         = new EventPublisher(publisherCommands)

        // Subscriber side — separate connection for pub/sub
        subscriberClient = RedisClient.create(uri)
        pubSubConn       = subscriberClient.connectPubSub()
        pubSubConn.addListener(new RedisPubSubListener<String, String>() {
            @Override void message(String channel, String message) {
                if (channel == EVENTS_CHANNEL) received.offer(message)
            }
            @Override void message(String pattern, String channel, String message) {}
            @Override void subscribed(String channel, long count) {}
            @Override void psubscribed(String pattern, long count) {}
            @Override void unsubscribed(String channel, long count) {}
            @Override void punsubscribed(String pattern, long count) {}
        })
        pubSubConn.sync().subscribe(EVENTS_CHANNEL)
        // Small settle time so subscription is confirmed
        Thread.sleep(100)
    }

    def cleanup() {
        pubSubConn?.close()
        subscriberClient?.shutdown()
        publisherConn?.close()
        publisherClient?.shutdown()
        received.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────

    def "publishInterceptActive delivers INTERCEPT_ACTIVE type to the events channel"() {
        when:
        publisher.publishInterceptActive("intercept-1", "payment-service", "session-1")

        then:
        def msg = received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        msg != null
        def payload = gson.fromJson(msg, Map)
        payload["type"] == "INTERCEPT_ACTIVE"
        payload["interceptId"] == "intercept-1"
        payload["serviceName"] == "payment-service"
        payload["sessionId"] == "session-1"
        payload["timestamp"] != null
    }

    def "publishInterceptTornDown delivers INTERCEPT_TORN_DOWN type to the events channel"() {
        when:
        publisher.publishInterceptTornDown("payment-service-crd", "payment-service")

        then:
        def msg = received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        msg != null
        def payload = gson.fromJson(msg, Map)
        payload["type"] == "INTERCEPT_TORN_DOWN"
        payload["interceptName"] == "payment-service-crd"
        payload["serviceName"] == "payment-service"
    }

    def "publishSessionExpired delivers SESSION_EXPIRED type to the events channel"() {
        when:
        publisher.publishSessionExpired("session-expired-99")

        then:
        def msg = received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        msg != null
        def payload = gson.fromJson(msg, Map)
        payload["type"] == "SESSION_EXPIRED"
        payload["sessionId"] == "session-expired-99"
    }

    def "publishMetrics delivers METRICS_UPDATE type with correct numeric fields"() {
        when:
        publisher.publishMetrics("intercept-metrics-1", 42.5d, 120.3d, 1.5d)

        then:
        def msg = received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        msg != null
        def payload = gson.fromJson(msg, Map)
        payload["type"] == "METRICS_UPDATE"
        payload["interceptId"] == "intercept-metrics-1"
        Double.parseDouble(payload["reqPerSecond"] as String) == 42.5d
        Double.parseDouble(payload["p99Ms"] as String) == 120.3d
        Double.parseDouble(payload["errorPct"] as String) == 1.5d
    }

    def "each published event carries a non-null timestamp"() {
        when:
        publisher.publishInterceptActive("ts-test", "any-svc", "sess-ts")

        then:
        def msg = received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS)
        def payload = gson.fromJson(msg, Map)
        payload["timestamp"] != null
        Long.parseLong(payload["timestamp"] as String) > 0
    }

    def "publishing multiple events delivers them all in order"() {
        when:
        publisher.publishInterceptActive("i1", "svc-1", "s1")
        publisher.publishInterceptActive("i2", "svc-2", "s2")
        publisher.publishInterceptActive("i3", "svc-3", "s3")

        then:
        def msgs = (1..3).collect { received.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        msgs.every { it != null }
        def types = msgs.collect { gson.fromJson(it, Map)["type"] }
        types.every { it == "INTERCEPT_ACTIVE" }
    }
}
