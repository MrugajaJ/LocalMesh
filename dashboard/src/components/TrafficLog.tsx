import { useState, useRef, useEffect, useMemo } from 'react';
import type { TrafficEvent } from '../types';
import { formatTime } from '../utils/time';

interface Props {
  events:       TrafficEvent[];
  interceptName?: string;
}

function statusRowClass(code: number): string {
  if (code >= 500) return 'status-5xx';
  if (code >= 400) return 'status-4xx';
  if (code >= 300) return 'status-3xx';
  return 'status-2xx';
}

const METHODS = ['ALL', 'GET', 'POST', 'PUT', 'DELETE', 'PATCH'];

export function TrafficLog({ events, interceptName }: Props) {
  const [methodFilter,  setMethodFilter]  = useState('ALL');
  const [statusFilter,  setStatusFilter]  = useState('ALL');
  const [pathFilter,    setPathFilter]    = useState('');
  const [autoScroll,    setAutoScroll]    = useState(true);

  const scrollRef = useRef<HTMLDivElement>(null);

  // Auto-scroll to bottom on new events
  useEffect(() => {
    if (autoScroll && scrollRef.current) {
      scrollRef.current.scrollTop = 0; // newest at top
    }
  }, [events, autoScroll]);

  const filtered = useMemo(() => {
    return events
      .filter(e => methodFilter === 'ALL' || e.method === methodFilter)
      .filter(e => {
        if (statusFilter === 'ALL') return true;
        if (statusFilter === '2xx') return e.statusCode >= 200 && e.statusCode < 300;
        if (statusFilter === '4xx') return e.statusCode >= 400 && e.statusCode < 500;
        if (statusFilter === '5xx') return e.statusCode >= 500;
        return true;
      })
      .filter(e => pathFilter === '' || e.path.toLowerCase().includes(pathFilter.toLowerCase()))
      .slice(0, 200);
  }, [events, methodFilter, statusFilter, pathFilter]);

  if (!interceptName) {
    return (
      <div className="traffic-panel" style={{ alignItems: 'center', justifyContent: 'center', display: 'flex' }}>
        <div className="traffic-empty">
          <span style={{ fontSize: 28, opacity: 0.2 }}>◎</span>
          <span>Select an intercept to view live traffic</span>
        </div>
      </div>
    );
  }

  return (
    <div className="traffic-panel">
      {/* Toolbar */}
      <div className="traffic-toolbar">
        <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--text-muted)' }}>
          /{interceptName}
        </span>

        <input
          className="filter-input"
          type="text"
          placeholder="Filter path..."
          value={pathFilter}
          onChange={e => setPathFilter(e.target.value)}
          id="traffic-path-filter"
        />

        <select
          className="filter-select"
          value={methodFilter}
          onChange={e => setMethodFilter(e.target.value)}
          id="traffic-method-filter"
        >
          {METHODS.map(m => <option key={m} value={m}>{m}</option>)}
        </select>

        <select
          className="filter-select"
          value={statusFilter}
          onChange={e => setStatusFilter(e.target.value)}
          id="traffic-status-filter"
        >
          <option value="ALL">All Status</option>
          <option value="2xx">2xx OK</option>
          <option value="4xx">4xx Client Error</option>
          <option value="5xx">5xx Server Error</option>
        </select>

        <span style={{ fontSize: 11, color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
          {filtered.length} events
        </span>

        <div
          className="autoscroll-toggle"
          onClick={() => setAutoScroll(v => !v)}
          id="autoscroll-toggle"
        >
          <div className={`toggle-check ${autoScroll ? 'on' : ''}`}>
            {autoScroll && '✓'}
          </div>
          Auto-scroll
        </div>
      </div>

      {/* Traffic rows */}
      <div className="traffic-scroll" ref={scrollRef}>
        {filtered.length === 0 ? (
          <div className="traffic-empty" style={{ height: '200px' }}>
            <span style={{ opacity: 0.2, fontSize: 24 }}>⊙</span>
            <span>No traffic yet</span>
            <span style={{ fontSize: 11, color: 'var(--text-muted)' }}>
              Waiting for requests to {interceptName}...
            </span>
          </div>
        ) : (
          filtered.map(event => (
            <TrafficRow key={event.id} event={event} />
          ))
        )}
      </div>
    </div>
  );
}

function TrafficRow({ event }: { event: TrafficEvent }) {
  const { method, path, statusCode, latencyMs, timestamp } = event;
  const rowClass = statusRowClass(statusCode);

  return (
    <div className={`traffic-row ${rowClass}`}>
      <span className="traffic-time">{formatTime(timestamp)}</span>
      <span className="traffic-method">{method}</span>
      <span className="traffic-path">{path}</span>
      <span className="traffic-status">{statusCode}</span>
      <span className="traffic-latency">{latencyMs}ms</span>
    </div>
  );
}
