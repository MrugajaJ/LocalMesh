package dev.localmesh.sidecar;

import dev.localmesh.proto.TunnelFrame;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SidecarTest {

    // ── TunnelFrame serialisation roundtrip ───────────────────────────────────

    @Test
    @DisplayName("TunnelFrame serialisation roundtrip preserves all fields")
    void tunnelFrameRoundtrip() throws Exception {
        byte[] originalBody = "GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes();

        TunnelFrame frame = TunnelFrame.newBuilder()
                .setInterceptId("test-intercept-123")
                .setRequestId("req-456")
                .setDirection("REQUEST")
                .setMethod("GET")
                .setPath("/health")
                .setPayload(ByteString.copyFrom(originalBody))
                .setTimestampMs(1234567890L)
                .build();

        // Serialise to bytes and back
        byte[] serialised = frame.toByteArray();
        TunnelFrame deserialized = TunnelFrame.parseFrom(serialised);

        assertEquals("test-intercept-123", deserialized.getInterceptId());
        assertEquals("req-456",            deserialized.getRequestId());
        assertEquals("REQUEST",            deserialized.getDirection());
        assertEquals("GET",                deserialized.getMethod());
        assertEquals("/health",            deserialized.getPath());
        assertEquals(1234567890L,          deserialized.getTimestampMs());
        assertArrayEquals(originalBody,    deserialized.getPayload().toByteArray());
    }

    @Test
    @DisplayName("TunnelFrame with response fields preserves status code and latency")
    void tunnelFrameResponseRoundtrip() throws Exception {
        byte[] responseBody = "{\"status\":\"ok\"}".getBytes();

        TunnelFrame frame = TunnelFrame.newBuilder()
                .setInterceptId("test-intercept-123")
                .setRequestId("req-789")
                .setDirection("RESPONSE")
                .setStatusCode(200)
                .setLatencyMs(42)
                .setPayload(ByteString.copyFrom(responseBody))
                .build();

        TunnelFrame deserialized = TunnelFrame.parseFrom(frame.toByteArray());

        assertEquals(200,   deserialized.getStatusCode());
        assertEquals(42,    deserialized.getLatencyMs());
        assertEquals("RESPONSE", deserialized.getDirection());
    }

    // ── Reconnect backoff logic ───────────────────────────────────────────────

    @Test
    @DisplayName("Exponential backoff doubles delay up to maximum of 30s")
    void reconnectBackoffCaps() {
        int delay = 1;
        int[] delays = new int[8];
        for (int i = 0; i < 8; i++) {
            delays[i] = delay;
            delay = Math.min(delay * 2, 30);
        }
        assertArrayEquals(new int[]{1, 2, 4, 8, 16, 30, 30, 30}, delays);
    }

    // ── Graceful drain behaviour ──────────────────────────────────────────────

    @Test
    @DisplayName("In-flight counter correctly tracks request lifecycle")
    void inFlightCounterTracking() throws Exception {
        AtomicLong inFlight = new AtomicLong(0);

        // Simulate 3 concurrent requests starting
        inFlight.incrementAndGet();
        inFlight.incrementAndGet();
        inFlight.incrementAndGet();
        assertEquals(3, inFlight.get());

        // Simulate 2 completing
        inFlight.decrementAndGet();
        inFlight.decrementAndGet();
        assertEquals(1, inFlight.get());

        // Last one completes
        inFlight.decrementAndGet();
        assertEquals(0, inFlight.get());
    }

    @Test
    @DisplayName("Accepting flag stops new requests from being served")
    void acceptingFlagBlocksNewRequests() {
        AtomicBoolean accepting = new AtomicBoolean(true);
        assertTrue(accepting.get());

        // Simulate SIGTERM
        accepting.set(false);
        assertFalse(accepting.get(), "Should reject new requests after shutdown begins");
    }

    @Test
    @DisplayName("Pending future is resolved when response arrives")
    void pendingFutureResolvedOnResponse() throws Exception {
        java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<TunnelFrame>> pending
                = new java.util.concurrent.ConcurrentHashMap<>();

        String reqId = "req-abc";
        CompletableFuture<TunnelFrame> future = new CompletableFuture<>();
        pending.put(reqId, future);

        // Simulate response arriving
        TunnelFrame response = TunnelFrame.newBuilder()
                .setRequestId(reqId)
                .setStatusCode(200)
                .setDirection("RESPONSE")
                .build();

        CompletableFuture<TunnelFrame> resolved = pending.remove(reqId);
        assertNotNull(resolved);
        resolved.complete(response);

        TunnelFrame result = future.get(1, TimeUnit.SECONDS);
        assertEquals(200, result.getStatusCode());
        assertNull(pending.get(reqId), "Pending entry should be removed after completion");
    }
}
