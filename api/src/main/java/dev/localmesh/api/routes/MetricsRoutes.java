package dev.localmesh.api.routes;

import com.google.gson.Gson;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.AsyncContext;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static spark.Spark.*;

/**
 * GET /api/metrics/live — Server-Sent Events endpoint.
 *
 * Subscribes to the Redis pub/sub channel "localmesh:events" and streams
 * each event as an SSE message to connected dashboard clients.
 */
public class MetricsRoutes {

    private static final Logger log = LoggerFactory.getLogger(MetricsRoutes.class);

    private final StatefulRedisPubSubConnection<String, String> pubSubConn;
    private final Gson gson;

    // Active SSE clients
    private final Set<PrintWriter> clients = ConcurrentHashMap.newKeySet();

    public MetricsRoutes(StatefulRedisPubSubConnection<String, String> pubSubConn, Gson gson) {
        this.pubSubConn = pubSubConn;
        this.gson       = gson;

        // Subscribe to Redis channel and broadcast to all SSE clients
        pubSubConn.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                if ("localmesh:events".equals(channel)) {
                    broadcast(message);
                }
            }
        });
        pubSubConn.async().subscribe("localmesh:events");
        log.info("Subscribed to Redis channel: localmesh:events");
    }

    public void register() {
        get("/api/metrics/live", (req, res) -> {
            res.raw().setContentType("text/event-stream");
            res.raw().setCharacterEncoding("UTF-8");
            res.raw().setHeader("Cache-Control", "no-cache");
            res.raw().setHeader("Connection",    "keep-alive");
            res.raw().setHeader("X-Accel-Buffering", "no");

            PrintWriter writer = res.raw().getWriter();

            // Send initial heartbeat
            writer.write("data: {\"type\":\"CONNECTED\"}\n\n");
            writer.flush();

            clients.add(writer);
            log.debug("SSE client connected. Total: {}", clients.size());

            // Keep the connection alive — SparkJava needs us to block here
            try {
                while (!writer.checkError()) {
                    Thread.sleep(15_000);
                    writer.write(": ping\n\n");
                    writer.flush();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                clients.remove(writer);
                log.debug("SSE client disconnected. Total: {}", clients.size());
            }

            return "";
        });
    }

    private void broadcast(String message) {
        String sseData = "data: " + message + "\n\n";
        clients.removeIf(writer -> {
            writer.write(sseData);
            writer.flush();
            return writer.checkError(); // remove if write failed
        });
    }
}
