package dev.localmesh.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Manages the developer session persisted to ~/.localmesh/session.json.
 * Also provides a simple HTTP client for REST API calls.
 */
public class SessionStore {

    private static final Path   SESSION_DIR  = Path.of(System.getProperty("user.home"), ".localmesh");
    private static final Path   SESSION_FILE = SESSION_DIR.resolve("session.json");
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ── Session POJO ─────────────────────────────────────────────────────────

    public static class Session {
        public String sessionId;
        public String developerName;
        public String apiUrl;
        public String createdAt;
        public String clusterEndpoint;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public static void save(Session session) throws IOException {
        Files.createDirectories(SESSION_DIR);
        MAPPER.writeValue(SESSION_FILE.toFile(), session);
    }

    public static Session load() throws IOException {
        if (!Files.exists(SESSION_FILE)) {
            throw new IllegalStateException(
                "No active session. Run 'localmesh connect' first.");
        }
        return MAPPER.readValue(SESSION_FILE.toFile(), Session.class);
    }

    public static boolean exists() {
        return Files.exists(SESSION_FILE);
    }

    public static void delete() throws IOException {
        Files.deleteIfExists(SESSION_FILE);
    }

    // ── HTTP API client ───────────────────────────────────────────────────────

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public static Map<?, ?> post(String url, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("API error " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readValue(resp.body(), Map.class);
    }

    public static Map<?, ?> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new RuntimeException("API error " + resp.statusCode() + ": " + resp.body());
        }
        return MAPPER.readValue(resp.body(), Map.class);
    }

    public static void delete(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .DELETE()
                .timeout(Duration.ofSeconds(15))
                .build();
        HTTP.send(req, HttpResponse.BodyHandlers.discarding());
    }

    public static java.util.List<?> getList(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(15))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readValue(resp.body(), java.util.List.class);
    }
}
