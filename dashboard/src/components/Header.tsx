import type { Intercept } from '../types';
import { formatDistanceToNow } from '../utils/time';

interface Props {
  intercepts: Intercept[];
  selectedId: string | null;
  onSelect:   (id: string) => void;
  onTearDown: (id: string) => void;
  onNewIntercept: () => void;
  connected:  boolean;
}

export function Header({ intercepts, selectedId, onSelect, onTearDown, onNewIntercept, connected }: Props) {
  const activeCount = intercepts.filter(i => i.status === 'ACTIVE').length;

  return (
    <header className="header">
      <div className="header-logo">
        [<span>LocalMesh</span>]
      </div>

      <div className="status-pill connected" style={{ opacity: connected ? 1 : 0.5 }}>
        <div className={`status-dot ${connected ? 'pulse' : ''}`} />
        {connected ? 'Connected' : 'Disconnected'}
      </div>

      {activeCount > 0 && (
        <div className="badge" title={`${activeCount} active intercept${activeCount !== 1 ? 's' : ''}`}>
          {activeCount}
        </div>
      )}

      <div className="header-spacer" />

      <button className="btn btn-primary" onClick={onNewIntercept} id="new-intercept-btn">
        + New Intercept
      </button>
    </header>
  );
}
