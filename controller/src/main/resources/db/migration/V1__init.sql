-- LocalMesh V1 Schema Migration
-- Creates the three core tables for session/intercept/traffic tracking

-- ─────────────────────────────────────────────────────────────────────────────
-- Developer sessions: who is connected via the CLI
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS developer_sessions (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    developer_name   VARCHAR(100) NOT NULL,
    tunnel_endpoint  VARCHAR(255),
    connected_at     TIMESTAMP   NOT NULL DEFAULT NOW(),
    last_heartbeat   TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_sessions_last_heartbeat ON developer_sessions(last_heartbeat);

-- ─────────────────────────────────────────────────────────────────────────────
-- Intercepts: which service a developer is intercepting
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS intercepts (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id   UUID        NOT NULL REFERENCES developer_sessions(id) ON DELETE CASCADE,
    service_name VARCHAR(100) NOT NULL,
    namespace    VARCHAR(100) NOT NULL DEFAULT 'default',
    local_port   INT         NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                                    -- PENDING | ACTIVE | TEARDOWN | FAILED | TORN_DOWN
    tunnel_endpoint VARCHAR(255),
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_intercepts_session_id ON intercepts(session_id);
CREATE INDEX IF NOT EXISTS idx_intercepts_status ON intercepts(status);
CREATE INDEX IF NOT EXISTS idx_intercepts_service_name ON intercepts(service_name, namespace);

-- ─────────────────────────────────────────────────────────────────────────────
-- Traffic events: per-request telemetry
-- ─────────────────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS traffic_events (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    intercept_id UUID        NOT NULL REFERENCES intercepts(id) ON DELETE CASCADE,
    request_id   VARCHAR(64),
    method       VARCHAR(10),
    path         VARCHAR(500),
    status_code  INT,
    latency_ms   INT,
    timestamp    TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_traffic_intercept_id ON traffic_events(intercept_id);
CREATE INDEX IF NOT EXISTS idx_traffic_timestamp ON traffic_events(timestamp DESC);
