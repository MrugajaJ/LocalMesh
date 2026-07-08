// ── Types shared across the dashboard ────────────────────────────────────────

export type InterceptStatus = 'PENDING' | 'ACTIVE' | 'TEARDOWN' | 'FAILED' | 'TORN_DOWN';

export interface Session {
  id: string;
  developerName: string;
  connectedAt: string;
  lastHeartbeat: string;
}

export interface Intercept {
  id: string;
  sessionId?: string;
  serviceName: string;
  namespace: string;
  localPort: number;
  status: InterceptStatus;
  tunnelEndpoint?: string;
  createdAt: string;
  // Live metrics (from SSE)
  reqPerSecond?: number;
  p99Latency?: number;
  errorPct?: number;
  requestCount?: number;
  avgLatencyMs?: number;
}

export interface TrafficEvent {
  id: string;
  interceptId: string;
  requestId?: string;
  method: string;
  path: string;
  statusCode: number;
  latencyMs: number;
  timestamp: string;
}

export interface TopologyNode {
  id: string;
  name: string;
  namespace: string;
  status?: InterceptStatus;
}

export interface TopologyEdge {
  source: string;
  target: string;
  requestCount: number;
  avgLatency: number;
}

export interface Topology {
  nodes: TopologyNode[];
  edges: TopologyEdge[];
}

export interface LiveEvent {
  type: 'INTERCEPT_ACTIVE' | 'INTERCEPT_TORN_DOWN' | 'SESSION_EXPIRED' | 'METRICS_UPDATE' | 'CONNECTED';
  interceptId?: string;
  serviceName?: string;
  sessionId?: string;
  reqPerSecond?: number;
  p99Ms?: number;
  errorPct?: number;
  timestamp?: string;
}
