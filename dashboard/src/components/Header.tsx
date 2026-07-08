import type { Intercept } from '../types';

interface Props {
  intercepts: Intercept[];
  selectedId: string | null;
  onSelect:   (id: string) => void;
  onTearDown: (id: string) => void;
  onNewIntercept: () => void;
  connectionState: 'connected' | 'reconnecting' | 'disconnected';
}

export function Header({ intercepts, onNewIntercept, connectionState }: Props) {
  const activeCount = intercepts.filter(i => i.status === 'ACTIVE').length;

  return (
    <header className="header">
      <div className="header-logo">
        [<span>LocalMesh</span>]
      </div>

      <div className={`status-pill ${connectionState}`} style={{ opacity: connectionState === 'disconnected' ? 0.5 : 1 }}>
        <div className={`status-dot ${connectionState === 'connected' ? 'pulse' : ''}`} />
        {connectionState === 'connected' ? 'Connected' : connectionState === 'reconnecting' ? 'Reconnecting...' : 'Disconnected'}
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
