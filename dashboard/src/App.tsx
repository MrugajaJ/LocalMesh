import { useState, useEffect, useCallback } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Header }            from './components/Header';
import { InterceptsPanel }   from './components/InterceptsPanel';
import { TrafficLog }        from './components/TrafficLog';
import { ServiceMap }        from './components/ServiceMap';
import { NewInterceptModal } from './components/NewInterceptModal';
import {
  useIntercepts, useTrafficLog, useTopology,
  useTearDownIntercept, subscribeToLiveEvents,
} from './api/client';
import { generateMockTrafficEvent, MOCK_INTERCEPTS } from './mock/mockData';
import type { Intercept, TrafficEvent, LiveEvent } from './types';
import './styles/global.css';

const IS_MOCK = import.meta.env.VITE_MOCK === 'true';
const MOCK_SESSION_ID = 'session-abc';

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, staleTime: 5_000 } },
});

function Dashboard() {
  const [selectedInterceptId, setSelectedInterceptId] = useState<string | null>(null);
  const [showModal,           setShowModal]           = useState(false);
  const [mapCollapsed,        setMapCollapsed]        = useState(false);
  const [liveTraffic,         setLiveTraffic]         = useState<TrafficEvent[]>([]);
  const [intercepts,          setIntercepts]          = useState<Intercept[]>([]);

  // React Query data
  const interceptsQuery = useIntercepts();
  const topologyQuery   = useTopology();
  const trafficQuery    = useTrafficLog(selectedInterceptId);
  const tearDown        = useTearDownIntercept();

  // Merge query data into local state
  useEffect(() => {
    if (interceptsQuery.data) setIntercepts(interceptsQuery.data);
  }, [interceptsQuery.data]);

  // Live traffic from query
  useEffect(() => {
    if (trafficQuery.data) {
      setLiveTraffic(trafficQuery.data.slice(0, 200));
    }
  }, [trafficQuery.data]);

  // SSE live events
  useEffect(() => {
    const cleanup = subscribeToLiveEvents((raw) => {
      const event = raw as LiveEvent;
      if (event.type === 'METRICS_UPDATE' && event.interceptId) {
        setIntercepts(prev => prev.map(i =>
          i.id === event.interceptId
            ? { ...i,
                reqPerSecond: parseFloat(String(event.reqPerSecond ?? i.reqPerSecond ?? 0)),
                p99Latency:   parseFloat(String(event.p99Ms ?? i.p99Latency ?? 0)),
                errorPct:     parseFloat(String(event.errorPct ?? i.errorPct ?? 0)),
              }
            : i
        ));
      }
      if (event.type === 'INTERCEPT_ACTIVE' || event.type === 'INTERCEPT_TORN_DOWN') {
        queryClient.invalidateQueries({ queryKey: ['intercepts'] });
      }
    });
    return cleanup;
  }, []);

  // In mock mode: generate live traffic events on a timer
  useEffect(() => {
    if (!IS_MOCK || !selectedInterceptId) return;
    const timer = setInterval(() => {
      const evt = generateMockTrafficEvent(selectedInterceptId);
      setLiveTraffic(prev => [evt, ...prev].slice(0, 200));
    }, 800);
    return () => clearInterval(timer);
  }, [selectedInterceptId]);

  const handleTearDown = useCallback((id: string) => {
    tearDown.mutate(id);
  }, [tearDown]);

  const selectedIntercept = intercepts.find(i => i.id === selectedInterceptId);
  const interceptedNames  = new Set(
    intercepts.filter(i => i.status === 'ACTIVE').map(i => i.serviceName)
  );
  const sessionId = IS_MOCK ? MOCK_SESSION_ID : (intercepts[0]?.sessionId ?? '');

  return (
    <div className="app-shell">
      <Header
        intercepts={intercepts}
        selectedId={selectedInterceptId}
        onSelect={setSelectedInterceptId}
        onTearDown={handleTearDown}
        onNewIntercept={() => setShowModal(true)}
        connected={!interceptsQuery.isError}
      />

      <div className="app-body">
        {/* Left panel — intercepts */}
        <InterceptsPanel
          intercepts={intercepts}
          selectedId={selectedInterceptId}
          onSelect={id => {
            setSelectedInterceptId(id);
            setLiveTraffic([]);
          }}
          onTearDown={handleTearDown}
          onNewIntercept={() => setShowModal(true)}
        />

        {/* Right panel — traffic + service map */}
        <div style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          <TrafficLog
            events={liveTraffic}
            interceptName={selectedIntercept?.serviceName}
          />
          <ServiceMap
            topology={topologyQuery.data ?? { nodes: [], edges: [] }}
            interceptedIds={interceptedNames}
            onNodeClick={name => {
              const match = intercepts.find(i => i.serviceName === name);
              if (match) { setSelectedInterceptId(match.id); setLiveTraffic([]); }
            }}
            collapsed={mapCollapsed}
            onToggleCollapse={() => setMapCollapsed(v => !v)}
          />
        </div>
      </div>

      {showModal && (
        <NewInterceptModal
          onClose={() => setShowModal(false)}
          topology={topologyQuery.data}
          sessionId={sessionId}
        />
      )}
    </div>
  );
}

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <Dashboard />
    </QueryClientProvider>
  );
}
