package dev.localmesh.controller.repository;

import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.jooq.impl.DSL.*;

/**
 * jOOQ-backed repository for intercept and session persistence.
 * No JPA/Hibernate — direct SQL via jOOQ.
 */
@Repository
public class InterceptRepository {

    private static final Logger log = LoggerFactory.getLogger(InterceptRepository.class);

    private final DSLContext dsl;

    public InterceptRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intercepts
    // ─────────────────────────────────────────────────────────────────────────

    public String createIntercept(String interceptId, String sessionId,
                                  String serviceName, String namespace,
                                  int localPort, String tunnelEndpoint) {
        dsl.insertInto(table("intercepts"))
                .set(field("id"), UUID.fromString(interceptId))
                .set(field("session_id"), UUID.fromString(sessionId))
                .set(field("service_name"), serviceName)
                .set(field("namespace"), namespace)
                .set(field("local_port"), localPort)
                .set(field("status"), "ACTIVE")
                .set(field("tunnel_endpoint"), tunnelEndpoint)
                .execute();
        log.debug("Created intercept: id={} service={}", interceptId, serviceName);
        return interceptId;
    }

    public void updateStatus(String crdName, String namespace, String status) {
        // Update the most recent intercept matching service name + namespace
        int updated = dsl.update(table("intercepts"))
                .set(field("status"), status)
                .set(field("updated_at"), LocalDateTime.now())
                .where(field("service_name").eq(crdName)
                        .and(field("namespace").eq(namespace))
                        .and(field("status").in("PENDING", "ACTIVE")))
                .execute();
        log.debug("Updated intercept status: crdName={} status={} rows={}", crdName, status, updated);
    }

    public void markTornDown(String crdName, String namespace) {
        dsl.update(table("intercepts"))
                .set(field("status"), "TORN_DOWN")
                .set(field("updated_at"), LocalDateTime.now())
                .where(field("service_name").eq(crdName)
                        .and(field("namespace").eq(namespace))
                        .and(field("status").in("ACTIVE", "TEARDOWN")))
                .execute();
    }

    public void recordTrafficEvent(String interceptId, String requestId,
                                   String method, String path,
                                   int statusCode, long latencyMs) {
        dsl.insertInto(table("traffic_events"))
                .set(field("id"), UUID.randomUUID())
                .set(field("intercept_id"), UUID.fromString(interceptId))
                .set(field("request_id"), requestId)
                .set(field("method"), method)
                .set(field("path"), path)
                .set(field("status_code"), statusCode)
                .set(field("latency_ms"), (int) latencyMs)
                .execute();
    }

    public int countActiveIntercepts() {
        return dsl.fetchCount(
                selectFrom(table("intercepts")).where(field("status").eq("ACTIVE")));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sessions
    // ─────────────────────────────────────────────────────────────────────────

    public String createSession(String developerName, String tunnelEndpoint) {
        String id = UUID.randomUUID().toString();
        dsl.insertInto(table("developer_sessions"))
                .set(field("id"), UUID.fromString(id))
                .set(field("developer_name"), developerName)
                .set(field("tunnel_endpoint"), tunnelEndpoint)
                .execute();
        return id;
    }

    public void updateHeartbeat(String sessionId) {
        dsl.update(table("developer_sessions"))
                .set(field("last_heartbeat"), LocalDateTime.now())
                .where(field("id").eq(UUID.fromString(sessionId)))
                .execute();
    }

    public List<String> findExpiredSessions(int timeoutSeconds) {
        LocalDateTime threshold = LocalDateTime.now().minusSeconds(timeoutSeconds);
        return dsl.select(field("id", String.class))
                .from(table("developer_sessions"))
                .where(field("last_heartbeat").lessThan(threshold))
                .fetchInto(String.class);
    }

    public void tearDownActiveInterceptsForSession(String sessionId) {
        dsl.update(table("intercepts"))
                .set(field("status"), "TORN_DOWN")
                .set(field("updated_at"), LocalDateTime.now())
                .where(field("session_id").eq(UUID.fromString(sessionId))
                        .and(field("status").in("ACTIVE", "PENDING")))
                .execute();
    }
}
