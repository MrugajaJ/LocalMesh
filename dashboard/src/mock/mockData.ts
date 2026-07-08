// ── Mock data for VITE_MOCK=true mode ────────────────────────────────────────
// Simulates 3 services with live traffic so the dashboard can demo without a cluster.

import type { Intercept, TrafficEvent, Topology, LiveEvent } from '../types';

export const MOCK_INTERCEPTS: Intercept[] = [
  {
    id: 'intercept-001',
    sessionId: 'session-abc',
    serviceName: 'payment-service',
    namespace: 'default',
    localPort: 8080,
    status: 'ACTIVE',
    tunnelEndpoint: 'localmesh-controller.default.svc.cluster.local:50051',
    createdAt: new Date(Date.now() - 14 * 60_000).toISOString(),
    reqPerSecond: 12.4,
    p99Latency: 87,
    errorPct: 1.2,
    requestCount: 1042,
    avgLatencyMs: 34,
  },
  {
    id: 'intercept-002',
    sessionId: 'session-def',
    serviceName: 'order-service',
    namespace: 'default',
    localPort: 3000,
    status: 'ACTIVE',
    createdAt: new Date(Date.now() - 5 * 60_000).toISOString(),
    reqPerSecond: 4.1,
    p99Latency: 210,
    errorPct: 0,
    requestCount: 211,
    avgLatencyMs: 68,
  },
  {
    id: 'intercept-003',
    sessionId: 'session-ghi',
    serviceName: 'notification-service',
    namespace: 'staging',
    localPort: 4000,
    status: 'TEARDOWN',
    createdAt: new Date(Date.now() - 60 * 60_000).toISOString(),
    reqPerSecond: 0,
    p99Latency: 0,
    errorPct: 0,
    requestCount: 9823,
    avgLatencyMs: 12,
  },
];

const METHODS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH'];
const PATHS   = [
  '/payments/process', '/payments/refund', '/payments/status',
  '/orders/create', '/orders/123', '/orders/list',
  '/notifications/send', '/notifications/status',
  '/health', '/metrics',
];

let trafficSeq = 0;
export function generateMockTrafficEvent(interceptId: string): TrafficEvent {
  const statuses = [200, 200, 200, 201, 204, 400, 404, 500];
  const status   = statuses[Math.floor(Math.random() * statuses.length)];
  return {
    id:          `evt-${++trafficSeq}`,
    interceptId,
    requestId:   `req-${trafficSeq}`,
    method:      METHODS[Math.floor(Math.random() * METHODS.length)],
    path:        PATHS[Math.floor(Math.random() * PATHS.length)],
    statusCode:  status,
    latencyMs:   Math.floor(Math.random() * 200) + 5,
    timestamp:   new Date().toISOString(),
  };
}

export const MOCK_TOPOLOGY: Topology = {
  nodes: [
    { id: 'order-service',        name: 'order-service',        namespace: 'default', status: 'ACTIVE' },
    { id: 'payment-service',      name: 'payment-service',      namespace: 'default', status: 'ACTIVE' },
    { id: 'notification-service', name: 'notification-service', namespace: 'default', status: 'TORN_DOWN' },
    { id: 'auth-service',         name: 'auth-service',         namespace: 'default' },
    { id: 'inventory-service',    name: 'inventory-service',    namespace: 'default' },
  ],
  edges: [
    { source: 'order-service',   target: 'payment-service',      requestCount: 8420, avgLatency: 34 },
    { source: 'order-service',   target: 'inventory-service',    requestCount: 5100, avgLatency: 22 },
    { source: 'payment-service', target: 'notification-service', requestCount: 4100, avgLatency: 12 },
    { source: 'order-service',   target: 'auth-service',         requestCount: 9200, avgLatency: 8  },
  ],
};
