import type { Intercept, InterceptStatus } from '../types';
import { formatDistanceToNow } from '../utils/time';

interface Props {
  intercepts:  Intercept[];
  selectedId:  string | null;
  onSelect:    (id: string) => void;
  onTearDown:  (id: string) => void;
  onNewIntercept: () => void;
}

function statusClass(status: InterceptStatus): string {
  return status.toLowerCase();
}

export function InterceptsPanel({ intercepts, selectedId, onSelect, onTearDown, onNewIntercept }: Props) {
  return (
    <aside className="intercepts-panel">
      <div className="panel-header">
        <span className="panel-title">Active Intercepts</span>
        <button className="btn" onClick={onNewIntercept} style={{ fontSize: 11, padding: '4px 10px' }}>
          + New
        </button>
      </div>

      <div className="intercepts-list">
        {intercepts.length === 0 ? (
          <div className="empty-state">
            <div className="empty-icon">⊙</div>
            <div className="empty-title">No active intercepts</div>
            <div className="empty-hint">
              Run <code className="mono" style={{ color: 'var(--cyan)' }}>localmesh intercept &lt;service&gt; --port 8080</code>
              <br/>or click <strong>+ New Intercept</strong> above.
            </div>
          </div>
        ) : (
          intercepts.map(intercept => (
            <InterceptCard
              key={intercept.id}
              intercept={intercept}
              selected={intercept.id === selectedId}
              onSelect={() => onSelect(intercept.id)}
              onTearDown={() => onTearDown(intercept.id)}
            />
          ))
        )}
      </div>
    </aside>
  );
}

function InterceptCard({ intercept, selected, onSelect, onTearDown }: {
  intercept:  Intercept;
  selected:   boolean;
  onSelect:   () => void;
  onTearDown: () => void;
}) {
  const { status, serviceName, createdAt, reqPerSecond, p99Latency, errorPct } = intercept;

  return (
    <div
      className={`intercept-card ${statusClass(status)} ${selected ? 'selected' : ''}`}
      onClick={onSelect}
      id={`intercept-card-${intercept.id}`}
    >
      <div className="intercept-name">
        <span>{serviceName}</span>
        <span style={{ fontSize: 10, color: 'var(--text-muted)', fontWeight: 400 }}>
          :{intercept.localPort}
        </span>
      </div>

      <div className="intercept-meta">
        {intercept.namespace} · {formatDistanceToNow(createdAt)} ago
      </div>

      <div className="intercept-metrics">
        <MetricItem
          value={reqPerSecond != null ? reqPerSecond.toFixed(1) : '—'}
          label="req/s"
        />
        <MetricItem
          value={p99Latency != null ? `${p99Latency}ms` : '—'}
          label="p99"
        />
        <MetricItem
          value={errorPct != null ? `${errorPct.toFixed(1)}%` : '—'}
          label="errors"
          danger={errorPct != null && errorPct > 5}
        />
      </div>

      <div className="intercept-footer">
        <StatusIndicator status={status} />
        {status === 'ACTIVE' && (
          <button
            className="btn btn-danger"
            onClick={e => { e.stopPropagation(); onTearDown(); }}
            id={`tear-down-${intercept.id}`}
          >
            Tear Down
          </button>
        )}
      </div>
    </div>
  );
}

function MetricItem({ value, label, danger }: { value: string; label: string; danger?: boolean }) {
  return (
    <div className="metric-item">
      <span className="metric-value" style={{ color: danger ? 'var(--red)' : undefined }}>
        {value}
      </span>
      <span className="metric-label">{label}</span>
    </div>
  );
}

function StatusIndicator({ status }: { status: InterceptStatus }) {
  const cls   = statusClass(status);
  const pulse = status === 'ACTIVE';
  return (
    <div className={`status-indicator ${cls}`}>
      <div className={`status-ring ${pulse ? 'pulsing' : ''}`} />
      {status}
    </div>
  );
}
