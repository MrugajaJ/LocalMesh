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
 * Session lifecycle routes:
 *   POST   /api/sessions
 *   GET    /api/sessions
 *   DELETE /api/sessions/:sessionId
 *   POST   /api/sessions/:sessionId/heartbeat
 */
public class SessionRoutes {

    private static final Logger log = LoggerFactory.getLogger(SessionRoutes.class);

    private final DSLContext dsl;
    private final RedisCommands<String, String> redis;
    private final Gson gson;

    public SessionRoutes(DSLContext dsl, RedisCommands<String, String> redis, Gson gson) {
        this.dsl   = dsl;
        this.redis = redis;
        this.gson  = gson;
    }

    public void register() {

        // POST /api/sessions — create session
        post("/api/sessions", (req, res) -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = gson.fromJson(req.body(), Map.class);
            String developerName = body != null && body.get("developerName") != null
                    ? body.get("developerName").toString()
                    : "anonymous";

            String id = UUID.randomUUID().toString();
            LocalDateTime now = LocalDateTime.now();

            dsl.insertInto(table("developer_sessions"))
                    .set(field("id"), UUID.fromString(id))
                    .set(field("developer_name"), developerName)
                    .set(field("connected_at"), now)
                    .set(field("last_heartbeat"), now)
                    .execute();

            // Set Redis heartbeat TTL key
            redis.setex("localmesh:session:" + id, 35, "alive");

            res.status(201);
            return gson.toJson(Map.of(
                    "sessionId",     id,
                    "developerName", developerName,
                    "createdAt",     now.toString()
            ));
        });

        // GET /api/sessions — active sessions (heartbeat within 30s)
        get("/api/sessions", (req, res) -> {
            LocalDateTime threshold = LocalDateTime.now().minusSeconds(30);
            List<Map<String, Object>> sessions = dsl
                    .select(field("id"), field("developer_name"), field("connected_at"), field("last_heartbeat"))
                    .from(table("developer_sessions"))
                    .where(field("last_heartbeat").greaterThan(threshold))
                    .fetchMaps();
            return gson.toJson(sessions);
        });

        // DELETE /api/sessions/:sessionId
        delete("/api/sessions/:sessionId", (req, res) -> {
            String sessionId = req.params(":sessionId");

            // Tear down active intercepts
            dsl.update(table("intercepts"))
                    .set(field("status"), "TORN_DOWN")
                    .set(field("updated_at"), LocalDateTime.now())
                    .where(field("session_id").eq(UUID.fromString(sessionId))
                            .and(field("status").in("ACTIVE", "PENDING")))
                    .execute();

            // Delete session
            dsl.deleteFrom(table("developer_sessions"))
                    .where(field("id").eq(UUID.fromString(sessionId)))
                    .execute();

            redis.del("localmesh:session:" + sessionId);
            res.status(204);
            return "";
        });

        // POST /api/sessions/:sessionId/heartbeat
        post("/api/sessions/:sessionId/heartbeat", (req, res) -> {
            String sessionId = req.params(":sessionId");
            LocalDateTime now = LocalDateTime.now();

            int updated = dsl.update(table("developer_sessions"))
                    .set(field("last_heartbeat"), now)
                    .where(field("id").eq(UUID.fromString(sessionId)))
                    .execute();

            if (updated == 0) {
                res.status(404);
                return gson.toJson(Map.of("error", "Session not found"));
            }

            redis.setex("localmesh:session:" + sessionId, 35, "alive");
            return gson.toJson(Map.of("ok", true, "lastHeartbeat", now.toString()));
        });
    }
}
