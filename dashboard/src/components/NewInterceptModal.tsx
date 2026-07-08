import { useState } from 'react';
import type { Topology } from '../types';
import { useCreateIntercept } from '../api/client';

interface Props {
  onClose:   () => void;
  topology:  Topology | undefined;
  sessionId: string;
}

export function NewInterceptModal({ onClose, topology, sessionId }: Props) {
  const [serviceName, setServiceName] = useState('');
  const [localPort,   setLocalPort]   = useState('8080');
  const [namespace,   setNamespace]   = useState('default');
  const [statusMsg,   setStatusMsg]   = useState('');

  const createIntercept = useCreateIntercept();

  const serviceNames = topology?.nodes.map(n => n.name) ?? [];

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!serviceName || !localPort) return;

    setStatusMsg('Injecting sidecar into pod...');

    try {
      await createIntercept.mutateAsync({
        sessionId,
        serviceName,
        namespace,
        localPort: parseInt(localPort, 10),
      });
      setStatusMsg('');
      onClose();
    } catch (err: unknown) {
      setStatusMsg(`Error: ${err instanceof Error ? err.message : String(err)}`);
    }
  };

  return (
    <div className="modal-overlay" onClick={e => e.target === e.currentTarget && onClose()}>
      <div className="modal" id="new-intercept-modal">
        <div className="modal-title">
          <span className="title-bracket">[</span>
          New Intercept
          <span className="title-bracket">]</span>
        </div>

        <form onSubmit={handleSubmit}>
          <div className="form-group">
            <label className="form-label" htmlFor="service-name-input">Service Name</label>
            <input
              id="service-name-input"
              className="form-input"
              type="text"
              list="service-suggestions"
              value={serviceName}
              onChange={e => setServiceName(e.target.value)}
              placeholder="payment-service"
              required
              autoFocus
            />
            <datalist id="service-suggestions">
              {serviceNames.map(name => (
                <option key={name} value={name} />
              ))}
            </datalist>
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="local-port-input">Local Port</label>
            <input
              id="local-port-input"
              className="form-input"
              type="number"
              value={localPort}
              onChange={e => setLocalPort(e.target.value)}
              placeholder="8080"
              min={1}
              max={65535}
              required
            />
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="namespace-input">Namespace</label>
            <input
              id="namespace-input"
              className="form-input"
              type="text"
              value={namespace}
              onChange={e => setNamespace(e.target.value)}
              placeholder="default"
            />
          </div>

          {statusMsg && (
            <div className="modal-status">
              {createIntercept.isPending ? (
                <div className="spinner" />
              ) : (
                <span style={{ color: statusMsg.startsWith('Error') ? 'var(--red)' : 'var(--text-muted)' }}>●</span>
              )}
              {statusMsg}
            </div>
          )}

          <div className="modal-actions">
            <button type="button" className="btn" onClick={onClose} id="cancel-intercept-btn">
              Cancel
            </button>
            <button
              type="submit"
              className="btn btn-primary"
              disabled={createIntercept.isPending}
              id="submit-intercept-btn"
            >
              {createIntercept.isPending ? 'Creating...' : 'Intercept'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
