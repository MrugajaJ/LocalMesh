package dev.localmesh.api;

import com.google.gson.Gson;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.localmesh.api.routes.*;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

import static spark.Spark.*;

/**
 * LocalMesh REST API — SparkJava-based HTTP server.
 *
 * Provides endpoints for the dashboard and CLI to manage sessions,
 * intercepts, traffic data, and live SSE metric streams.
 */
public class ApiMain {

    private static final Logger log = LoggerFactory.getLogger(ApiMain.class);
    private static final Gson   gson = new Gson();

    public static void main(String[] args) {
        // ── Configuration ─────────────────────────────────────────────────────
        String pgUrl      = env("POSTGRES_URL",     "jdbc:postgresql://localhost:5432/localmesh");
        String pgUser     = env("POSTGRES_USER",    "localmesh");
        String pgPassword = env("POSTGRES_PASSWORD","localmesh");
        String redisUri   = env("REDIS_URL",        "redis://localhost:6379");
        int    port       = Integer.parseInt(env("SERVER_PORT", "8080"));

        // ── Database ──────────────────────────────────────────────────────────
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(pgUrl);
        hikariConfig.setUsername(pgUser);
        hikariConfig.setPassword(pgPassword);
        hikariConfig.setMaximumPoolSize(10);
        HikariDataSource dataSource = new HikariDataSource(hikariConfig);

        // Run Flyway migrations
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate();

        DSLContext dsl = DSL.using(dataSource, SQLDialect.POSTGRES);

        // ── Redis ─────────────────────────────────────────────────────────────
        RedisClient redisClient = RedisClient.create(redisUri);
        StatefulRedisConnection<String, String> redisConn = redisClient.connect();
        StatefulRedisPubSubConnection<String, String> pubSubConn = redisClient.connectPubSub();

        // ── SparkJava server ──────────────────────────────────────────────────
        port(port);
        threadPool(20, 5, 30000);

        // CORS
        before("/*", (req, res) -> {
            res.header("Access-Control-Allow-Origin",  "http://localhost:5173");
            res.header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS");
            res.header("Access-Control-Allow-Headers", "Content-Type,Authorization");
        });
        options("/*", (req, res) -> { res.status(200); return ""; });

        // Request logging middleware
        before("/*", (req, res) -> req.attribute("startTime", System.currentTimeMillis()));
        afterAfter("/*", (req, res) -> {
            long start = req.attribute("startTime") != null ? (long)req.attribute("startTime") : System.currentTimeMillis();
            log.info("{} {} → {} ({}ms)", req.requestMethod(), req.pathInfo(), res.status(),
                    System.currentTimeMillis() - start);
        });

        // JSON content type default
        before("/api/*", (req, res) -> res.type("application/json"));

        // ── Routes ────────────────────────────────────────────────────────────
        SessionRoutes    sessionRoutes    = new SessionRoutes(dsl, redisConn.sync(), gson);
        InterceptRoutes  interceptRoutes  = new InterceptRoutes(dsl, redisConn.sync(), gson);
        TrafficRoutes    trafficRoutes    = new TrafficRoutes(dsl, gson);
        TopologyRoutes   topologyRoutes   = new TopologyRoutes(dsl, redisConn.sync(), gson);
        MetricsRoutes    metricsRoutes    = new MetricsRoutes(pubSubConn, gson);

        sessionRoutes.register();
        interceptRoutes.register();
        trafficRoutes.register();
        topologyRoutes.register();
        metricsRoutes.register();

        // Health check
        get("/health", (req, res) -> gson.toJson(new java.util.HashMap<>() {{
            put("status", "ok");
            put("timestamp", Instant.now().toString());
        }}));

        // Global error handler
        exception(Exception.class, (e, req, res) -> {
            log.error("Unhandled exception: {} {}", req.requestMethod(), req.pathInfo(), e);
            res.status(500);
            res.type("application/json");
            res.body(gson.toJson(java.util.Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error")));
        });

        log.info("LocalMesh API started on port {}", port);
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : def;
    }
}
