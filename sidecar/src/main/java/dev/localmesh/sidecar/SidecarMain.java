package dev.localmesh.sidecar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.localmesh.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LocalMesh Sidecar Proxy
 *
 * Accepts inbound HTTP traffic on PROXY_PORT, serialises it into a TunnelFrame,
 * forwards over gRPC bidirectional stream to the controller tunnel, and streams
 * the response back to the caller.
 *
 * No Spring, no heavy frameworks — plain Java 21 + gRPC.
 */
public class SidecarMain {

    private static final Logger log = LoggerFactory.getLogger(SidecarMain.class);

    // ── Configuration from env vars ───────────────────────────────────────────
    private static final String TUNNEL_ENDPOINT = env("TUNNEL_ENDPOINT", "localhost:50051");
    private static final String INTERCEPT_ID    = env("INTERCEPT_ID", "local-dev");
    private static final int    TARGET_PORT     = Integer.parseInt(env("TARGET_PORT", "8080"));
    private static final int    PROXY_PORT      = Integer.parseInt(env("PROXY_PORT", "8081"));

    // ── State ─────────────────────────────────────────────────────────────────
    private static final AtomicBoolean   accepting    = new AtomicBoolean(true);
    private static final AtomicLong      inFlight     = new AtomicLong(0);
    private static final ConcurrentHashMap<String, CompletableFuture<TunnelFrame>>
                                         pending      = new ConcurrentHashMap<>();

    private static ManagedChannel                     channel;
    private static TunnelServiceGrpc.TunnelServiceStub tunnelStub;
    private static MetricsServiceGrpc.MetricsServiceBlockingStub metricsStub;
    private static StreamObserver<TunnelFrame>         tunnelSender;
    private static ScheduledExecutorService            scheduler;

    // ── Entry point ───────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {
        log.info("LocalMesh Sidecar starting: interceptId={} tunnelEndpoint={} proxyPort={}",
                INTERCEPT_ID, TUNNEL_ENDPOINT, PROXY_PORT);

        scheduler = Executors.newScheduledThreadPool(2);

        // Connect gRPC tunnel with exponential backoff
        connectWithBackoff(1);

        // Start HTTP server
        HttpServer httpServer = HttpServer.create(new InetSocketAddress(PROXY_PORT), 0);
        httpServer.createContext("/health", SidecarMain::handleHealth);
        httpServer.createContext("/",       SidecarMain::handleProxy);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        httpServer.start();
        log.info("Proxy HTTP server started on port {}", PROXY_PORT);

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("SIGTERM received — draining in-flight requests...");
            accepting.set(false);
            httpServer.stop(0);

            // Wait max 10s for in-flight requests to complete
            long deadline = System.currentTimeMillis() + 10_000;
            while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            }
            log.info("Drained. In-flight at shutdown: {}", inFlight.get());

            scheduler.shutdownNow();
            if (channel != null) channel.shutdownNow();
            log.info("Sidecar shut down cleanly");
        }));
    }

    // ── gRPC connection ───────────────────────────────────────────────────────

    static void connectWithBackoff(int delaySec) {
        try {
            channel = ManagedChannelBuilder.forTarget(TUNNEL_ENDPOINT)
                    .usePlaintext()
                    .build();
            tunnelStub  = TunnelServiceGrpc.newStub(channel);
            metricsStub = MetricsServiceGrpc.newBlockingStub(channel);

            tunnelSender = tunnelStub.connectTunnel(new StreamObserver<>() {
                @Override
                public void onNext(TunnelFrame frame) {
                    // Response from CLI side — match to pending request
                    CompletableFuture<TunnelFrame> future = pending.remove(frame.getRequestId());
                    if (future != null) future.complete(frame);
                }

                @Override
                public void onError(Throwable t) {
                    log.warn("Tunnel stream error: {}", t.getMessage());
                    scheduleReconnect(delaySec);
                }

                @Override
                public void onCompleted() {
                    log.info("Tunnel stream completed — reconnecting");
                    scheduleReconnect(delaySec);
                }
            });

            log.info("gRPC tunnel connected to {}", TUNNEL_ENDPOINT);

            // Start heartbeat ping every 10 seconds
            HeartbeatServiceGrpc.HeartbeatServiceBlockingStub heartbeat =
                    HeartbeatServiceGrpc.newBlockingStub(channel);
            scheduler.scheduleAtFixedRate(() -> {
                try {
                    heartbeat.ping(PingRequest.newBuilder()
                            .setInterceptId(INTERCEPT_ID)
                            .setTimestampMs(System.currentTimeMillis())
                            .build());
                } catch (Exception e) {
                    log.warn("Heartbeat ping failed: {}", e.getMessage());
                }
            }, 10, 10, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("Failed to connect gRPC tunnel: {}", e.getMessage());
            scheduleReconnect(delaySec);
        }
    }

    private static void scheduleReconnect(int delaySec) {
        int nextDelay = Math.min(delaySec * 2, 30);
        log.info("Reconnecting in {}s...", delaySec);
        scheduler.schedule(() -> connectWithBackoff(nextDelay), delaySec, TimeUnit.SECONDS);
    }

    // ── HTTP proxy handler ────────────────────────────────────────────────────

    static void handleProxy(HttpExchange exchange) throws IOException {
        if (!accepting.get()) {
            exchange.sendResponseHeaders(503, 0);
            exchange.close();
            return;
        }

        inFlight.incrementAndGet();
        long start     = System.currentTimeMillis();
        String reqId   = UUID.randomUUID().toString();

        try {
            // Read request body
            byte[] body = exchange.getRequestBody().readAllBytes();

            // Serialise request into TunnelFrame
            TunnelFrame requestFrame = TunnelFrame.newBuilder()
                    .setInterceptId(INTERCEPT_ID)
                    .setRequestId(reqId)
                    .setDirection("REQUEST")
                    .setMethod(exchange.getRequestMethod())
                    .setPath(exchange.getRequestURI().toString())
                    .setPayload(com.google.protobuf.ByteString.copyFrom(body))
                    .setTimestampMs(start)
                    .build();

            // Register pending future before sending
            CompletableFuture<TunnelFrame> responseFuture = new CompletableFuture<>();
            pending.put(reqId, responseFuture);

            // Send over tunnel
            if (tunnelSender == null) {
                throw new IllegalStateException("Tunnel not connected");
            }
            tunnelSender.onNext(requestFrame);

            // Wait for response (max 30s)
            TunnelFrame responseFrame = responseFuture.get(30, TimeUnit.SECONDS);

            int    statusCode = responseFrame.getStatusCode() > 0 ? responseFrame.getStatusCode() : 200;
            byte[] responseBody = responseFrame.getPayload().toByteArray();

            exchange.sendResponseHeaders(statusCode, responseBody.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBody);
            }

            long latencyMs = System.currentTimeMillis() - start;
            reportMetrics(reqId, exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(), statusCode, latencyMs);

            log.info("method={} path={} status={} latency_ms={}",
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    statusCode, latencyMs);

        } catch (TimeoutException e) {
            pending.remove(reqId);
            log.error("Request {} timed out", reqId);
            exchange.sendResponseHeaders(504, 0);
        } catch (Exception e) {
            pending.remove(reqId);
            log.error("Proxy error for request {}: {}", reqId, e.getMessage());
            exchange.sendResponseHeaders(502, 0);
        } finally {
            inFlight.decrementAndGet();
            exchange.close();
        }
    }

    static void handleHealth(HttpExchange exchange) throws IOException {
        String body = "{\"status\":\"ok\",\"interceptId\":\"" + INTERCEPT_ID + "\"}";
        byte[] bytes = body.getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
        exchange.close();
    }

    private static void reportMetrics(String reqId, String method, String path,
                                      int statusCode, long latencyMs) {
        try {
            if (metricsStub != null) {
                metricsStub.reportTraffic(TrafficReport.newBuilder()
                        .setInterceptId(INTERCEPT_ID)
                        .setRequestId(reqId)
                        .setMethod(method)
                        .setPath(path)
                        .setStatusCode(statusCode)
                        .setLatencyMs(latencyMs)
                        .setTimestampMs(System.currentTimeMillis())
                        .build());
            }
        } catch (Exception e) {
            log.warn("Failed to report metrics: {}", e.getMessage());
        }
    }

    private static String env(String key, String defaultValue) {
        String val = System.getenv(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }
}
