package dev.localmesh.sidecar;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GracefulDrainTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("In-flight requests complete before shutdown and new requests are rejected")
    void testGracefulDrain() throws Exception {
        AtomicBoolean accepting = new AtomicBoolean(true);
        AtomicLong inFlight = new AtomicLong(0);

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            if (!accepting.get()) {
                exchange.sendResponseHeaders(503, 0);
                exchange.close();
                return;
            }
            inFlight.incrementAndGet();
            try {
                // Simulate slow request
                Thread.sleep(200);
                exchange.sendResponseHeaders(200, 0);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.sendResponseHeaders(500, 0);
            } finally {
                inFlight.decrementAndGet();
                exchange.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(5));
        server.start();

        int port = server.getAddress().getPort();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/"))
                .GET()
                .build();

        // 1. Send 3 slow requests concurrently
        List<CompletableFuture<HttpResponse<Void>>> futures = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.discarding()));
        }

        // Wait up to 2 seconds to ensure they reach the server and are in-flight
        long startWait = System.currentTimeMillis();
        while (inFlight.get() == 0 && System.currentTimeMillis() - startWait < 2000) {
            Thread.sleep(10);
        }
        assertTrue(inFlight.get() > 0, "Requests should be in-flight");

        // 2. Trigger shutdown signal
        accepting.set(false);

        // 3. Try to send a new request (should be rejected)
        HttpResponse<Void> rejectedResponse = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertEquals(503, rejectedResponse.statusCode(), "New request should be rejected with 503");

        // 4. Wait for in-flight requests to complete (drain)
        long deadline = System.currentTimeMillis() + 5000;
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        
        server.stop(0);

        // Assert all 3 initial requests succeeded
        for (CompletableFuture<HttpResponse<Void>> future : futures) {
            HttpResponse<Void> response = future.get(1, TimeUnit.SECONDS);
            assertEquals(200, response.statusCode(), "In-flight request should complete successfully");
        }
        assertEquals(0, inFlight.get(), "All in-flight requests should be drained");
    }
}
