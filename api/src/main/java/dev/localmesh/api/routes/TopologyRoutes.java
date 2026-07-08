package dev.localmesh.api.routes;

import com.google.gson.Gson;
import io.lettuce.core.api.sync.RedisCommands;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.jooq.impl.DSL.*;
import static spark.Spark.*;

/**
 * GET /api/topology — service dependency graph inferred from traffic_events.
 *
 * Returns: { nodes: [{id, name, namespace}], edges: [{source, target, requestCount, avgLatency}] }
 */
public class TopologyRoutes {

    private final DSLContext dsl;
    private final RedisCommands<String, String> redis;
    private final Gson gson;

    public TopologyRoutes(DSLContext dsl, RedisCommands<String, String> redis, Gson gson) {
        this.dsl   = dsl;
        this.redis = redis;
        this.gson  = gson;
    }

    public void register() {
        get("/api/topology", (req, res) -> {
            // Check Redis cache first
            String cached = redis.get("localmesh:topology");
            if (cached != null) return cached;

            // Build topology from intercepts and traffic_events
            List<Map<String, Object>> intercepts = dsl
                    .select(field("id"), field("service_name"), field("namespace"), field("status"))
                    .from(table("intercepts"))
                    .fetchMaps();

            List<Map<String, Object>> nodes = new ArrayList<>();
            for (Map<String, Object> intercept : intercepts) {
                nodes.add(Map.of(
                        "id",        intercept.get("id").toString(),
                        "name",      intercept.getOrDefault("service_name", ""),
                        "namespace", intercept.getOrDefault("namespace", "default"),
                        "status",    intercept.getOrDefault("status", "UNKNOWN")
                ));
            }

            // Edges: aggregate traffic between services
            List<Map<String, Object>> edgeRows = dsl
                    .select(
                            field("i.service_name").as("source"),
                            field("te.path").as("target_path"),
                            count().as("request_count"),
                            avg(field("te.latency_ms", Double.class)).as("avg_latency"))
                    .from(table("traffic_events").as("te"))
                    .join(table("intercepts").as("i"))
                    .on(field("te.intercept_id").eq(field("i.id")))
                    .groupBy(field("i.service_name"), field("te.path"))
                    .limit(100)
                    .fetchMaps();

            List<Map<String, Object>> edges = new ArrayList<>();
            for (Map<String, Object> row : edgeRows) {
                Map<String, Object> edge = new HashMap<>();
                edge.put("source",       row.getOrDefault("source", ""));
                edge.put("target",       row.getOrDefault("target_path", ""));
                edge.put("requestCount", row.getOrDefault("request_count", 0));
                edge.put("avgLatency",   row.getOrDefault("avg_latency", 0));
                edges.add(edge);
            }

            String result = gson.toJson(Map.of("nodes", nodes, "edges", edges));

            // Cache for 5 seconds
            redis.setex("localmesh:topology", 5, result);
            return result;
        });
    }
}
