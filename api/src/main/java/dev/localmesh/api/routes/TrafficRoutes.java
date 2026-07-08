package dev.localmesh.api.routes;

import com.google.gson.Gson;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.jooq.impl.DSL.*;
import static spark.Spark.*;

/**
 * GET /api/intercepts/:interceptId/traffic?since=ISO&limit=100
 */
public class TrafficRoutes {

    private final DSLContext dsl;
    private final Gson gson;

    public TrafficRoutes(DSLContext dsl, Gson gson) {
        this.dsl  = dsl;
        this.gson = gson;
    }

    public void register() {
        get("/api/intercepts/:interceptId/traffic", (req, res) -> {
            String interceptId = req.params(":interceptId");
            String since       = req.queryParams("since");
            String limitParam  = req.queryParams("limit");
            int limit          = limitParam != null ? Math.min(Integer.parseInt(limitParam), 500) : 100;

            var query = dsl.select(
                            field("id"),
                            field("request_id"),
                            field("method"),
                            field("path"),
                            field("status_code"),
                            field("latency_ms"),
                            field("timestamp"))
                    .from(table("traffic_events"))
                    .where(field("intercept_id").eq(UUID.fromString(interceptId)));

            if (since != null && !since.isBlank()) {
                query = query.and(field("timestamp").greaterThan(LocalDateTime.parse(since)));
            }

            List<Map<String, Object>> events = query
                    .orderBy(field("timestamp").desc())
                    .limit(limit)
                    .fetchMaps();

            return gson.toJson(events);
        });
    }
}
