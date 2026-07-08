import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { Intercept, TrafficEvent, Topology, Session } from '../types';
import {
  MOCK_INTERCEPTS, MOCK_TOPOLOGY, generateMockTrafficEvent
} from '../mock/mockData';

const IS_MOCK   = import.meta.env.VITE_MOCK === 'true';
const API_BASE  = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';

// ── REST API helpers ──────────────────────────────────────────────────────────

export async function apiFetch<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(API_BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    const err = await res.text();
    throw new Error(err || `HTTP ${res.status}`);
  }
  return res.json() as Promise<T>;
}

// ── Sessions ──────────────────────────────────────────────────────────────────

export function useSessions() {
  return useQuery<Session[]>({
    queryKey: ['sessions'],
    queryFn:  () => IS_MOCK
      ? Promise.resolve([{ id: 'session-abc', developerName: 'alice', connectedAt: new Date().toISOString(), lastHeartbeat: new Date().toISOString() }])
      : apiFetch('/api/sessions'),
    refetchInterval: 10_000,
  });
}

// ── Intercepts ────────────────────────────────────────────────────────────────

export function useIntercepts(sessionId?: string) {
  return useQuery<Intercept[]>({
    queryKey: ['intercepts', sessionId],
    queryFn:  () => IS_MOCK
      ? Promise.resolve(MOCK_INTERCEPTS)
      : apiFetch(`/api/intercepts${sessionId ? `?sessionId=${sessionId}` : ''}`),
    refetchInterval: 5_000,
  });
}

export function useCreateIntercept() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (payload: { sessionId: string; serviceName: string; namespace: string; localPort: number }) =>
      IS_MOCK
        ? new Promise<Intercept>(resolve =>
            setTimeout(() => resolve({ ...MOCK_INTERCEPTS[0], id: `mock-${Date.now()}`, status: 'ACTIVE', ...payload }), 2000))
        : apiFetch<Intercept>('/api/intercepts', { method: 'POST', body: JSON.stringify(payload) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['intercepts'] }),
  });
}

export function useTearDownIntercept() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (interceptId: string) =>
      IS_MOCK
        ? Promise.resolve()
        : apiFetch(`/api/intercepts/${interceptId}`, { method: 'DELETE' }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['intercepts'] }),
  });
}

// ── Traffic log ───────────────────────────────────────────────────────────────

export function useTrafficLog(interceptId: string | null) {
  return useQuery<TrafficEvent[]>({
    queryKey: ['traffic', interceptId],
    queryFn:  () => {
      if (!interceptId) return Promise.resolve([]);
      if (IS_MOCK) return Promise.resolve(
        Array.from({ length: 20 }, () => generateMockTrafficEvent(interceptId))
      );
      return apiFetch(`/api/intercepts/${interceptId}/traffic?limit=200`);
    },
    enabled:         !!interceptId,
    refetchInterval: 2_000,
  });
}

// ── Topology ──────────────────────────────────────────────────────────────────

export function useTopology() {
  return useQuery<Topology>({
    queryKey: ['topology'],
    queryFn:  () => IS_MOCK ? Promise.resolve(MOCK_TOPOLOGY) : apiFetch('/api/topology'),
    refetchInterval: 15_000,
  });
}

// ── SSE live events ───────────────────────────────────────────────────────────

/**
 * Subscribe to the SSE stream and call onEvent for each message.
 * Returns a cleanup function.
 */
export function subscribeToLiveEvents(
  onEvent: (event: Record<string, unknown>) => void,
  onReconnectState?: (state: 'connected' | 'reconnecting' | 'disconnected') => void
): () => void {
  let isClosed = false;
  let source: EventSource | null = null;
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  let attempt = 0;

  if (IS_MOCK) {
    onReconnectState?.('connected');
    // In mock mode, generate synthetic events on a timer
    const timer = setInterval(() => {
      onEvent({
        type:         'METRICS_UPDATE',
        interceptId:  MOCK_INTERCEPTS[0].id,
        reqPerSecond: (Math.random() * 20).toFixed(1),
        p99Ms:        (Math.random() * 200 + 10).toFixed(0),
        errorPct:     (Math.random() * 5).toFixed(1),
        timestamp:    Date.now().toString(),
      });
    }, 1000);
    return () => { isClosed = true; clearInterval(timer); onReconnectState?.('disconnected'); };
  }

  function connect() {
    if (isClosed) return;
    source = new EventSource(API_BASE + '/api/metrics/live');
    
    source.onopen = () => {
      attempt = 0;
      onReconnectState?.('connected');
    };
    
    source.onmessage = (e) => {
      try { onEvent(JSON.parse(e.data)); } catch { /* ignore */ }
    };
    
    source.onerror = () => {
      source?.close();
      source = null;
      if (isClosed) return;
      
      onReconnectState?.('reconnecting');
      const backoff = Math.min(1000 * Math.pow(2, attempt), 30000);
      attempt++;
      console.warn(`[SSE] Connection error — reconnecting in ${backoff}ms`);
      reconnectTimer = setTimeout(connect, backoff);
    };
  }

  connect();

  return () => {
    isClosed = true;
    if (reconnectTimer) clearTimeout(reconnectTimer);
    source?.close();
    onReconnectState?.('disconnected');
  };
}
