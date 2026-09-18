package dev.localmesh.api.routes;

import com.google.gson.Gson;
import io.lettuce.core.api.sync.RedisCommands;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.jooq.impl.DSL.*;
import static spark.Spark.*;

/**
 * Intercept lifecycle routes:
 *   POST   /api/intercepts
 *   GET    /api/intercepts?sessionId=xxx
 *   DELETE /api/intercepts/:interceptId
 */
public class InterceptRoutes {

    private static final Logger log = LoggerFactory.getLogger(InterceptRoutes.class);

    private final DSLContext dsl;
    private final RedisCommands<String, String> redis;
    private final Gson gson;

    public InterceptRoutes(DSLContext dsl, RedisCommands<String, String> redis, Gson gson) {
        this.dsl   = dsl;
        this.redis = redis;
        this.gson  = gson;
    }

    public void register() {

        // POST /api/intercepts — create intercept
        post("/api/intercepts", (req, res) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = gson.fromJson(req.body(), Map.class);
            String sessionId     = (String) body.get("sessionId");
            String serviceName   = (String) body.get("serviceName");
            String namespace     = body.get("namespace") != null ? body.get("namespace").toString() : "default";
            int localPort        = ((Number) body.get("localPort")).intValue();

            String interceptId = UUID.randomUUID().toString();
            LocalDateTime now  = LocalDateTime.now();

            // Insert as PENDING
            dsl.insertInto(table("intercepts"))
                    .set(field("id"),          UUID.fromString(interceptId))
                    .set(field("session_id"),  UUID.fromString(sessionId))
                    .set(field("service_name"), serviceName)
                    .set(field("namespace"),   namespace)
                    .set(field("local_port"),  localPort)
                    .set(field("status"),      "PENDING")
                    .set(field("created_at"),  now)
                    .set(field("updated_at"),  now)
                    .execute();

            // Publish command to controller via Redis
            redis.publish("localmesh:commands", gson.toJson(Map.of(
                    "action",      "CREATE_INTERCEPT",
                    "interceptId", interceptId,
                    "serviceName", serviceName,
                    "namespace",   namespace,
                    "localPort",   localPort,
                    "sessionId",   sessionId
            )));

            // Poll for status change (max 30s, 1s intervals)
            String status      = "PENDING";
            String tunnelEndpoint = "";
            for (int i = 0; i < 30; i++) {
                Thread.sleep(1000);
                var row = dsl.select(field("status"), field("tunnel_endpoint"))
                        .from(table("intercepts"))
                        .where(field("id").eq(UUID.fromString(interceptId)))
                        .fetchOne();
                if (row != null) {
                    status = row.get(field("status", String.class));
                    String endpoint = row.get(field("tunnel_endpoint", String.class));
                    tunnelEndpoint = endpoint != null ? endpoint : "";
                    if ("ACTIVE".equals(status) || "FAILED".equals(status)) break;
                }
            }

            res.status(201);
            return gson.toJson(Map.of(
                    "interceptId",    interceptId,
                    "serviceName",    serviceName,
                    "namespace",      namespace,
                    "localPort",      localPort,
                    "status",         status,
                    "tunnelEndpoint", tunnelEndpoint != null ? tunnelEndpoint : ""
            ));
        });

        // GET /api/intercepts?sessionId=xxx
        get("/api/intercepts", (req, res) -> {
            String sessionId = req.queryParams("sessionId");
            List<Map<String, Object>> intercepts;

            if (sessionId != null && !sessionId.isBlank()) {
                intercepts = dsl.select(
                                field("id"),
                                field("service_name"),
                                field("namespace"),
                                field("local_port"),
                                field("status"),
                                field("tunnel_endpoint"),
                                field("created_at"))
                        .from(table("intercepts"))
                        .where(field("session_id").eq(UUID.fromString(sessionId)))
                        .orderBy(field("created_at").desc())
                        .fetchMaps();
            } else {
                intercepts = dsl.select(
                                field("id"),
                                field("service_name"),
                                field("namespace"),
                                field("local_port"),
                                field("status"),
                                field("tunnel_endpoint"),
                                field("created_at"))
                        .from(table("intercepts"))
                        .where(field("status").eq("ACTIVE"))
                        .orderBy(field("created_at").desc())
                        .fetchMaps();
            }

            return gson.toJson(intercepts);
        });

        // DELETE /api/intercepts/:interceptId
        delete("/api/intercepts/:interceptId", (req, res) -> {
            String interceptId = req.params(":interceptId");

            dsl.update(table("intercepts"))
                    .set(field("status"), "TEARDOWN")
                    .set(field("updated_at"), LocalDateTime.now())
                    .where(field("id").eq(UUID.fromString(interceptId)))
                    .execute();

            redis.publish("localmesh:commands", gson.toJson(Map.of(
                    "action",      "TEARDOWN_INTERCEPT",
                    "interceptId", interceptId
            )));

            res.status(202);
            return gson.toJson(Map.of("status", "TEARDOWN", "interceptId", interceptId));
        });
    }
}
