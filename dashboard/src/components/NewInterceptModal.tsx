import { useState } from 'react';
import type { Intercept, Topology } from '../types';
import { useCreateIntercept, apiFetch } from '../api/client';
import { useQueryClient } from '@tanstack/react-query';

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
  const [isPolling,   setIsPolling]   = useState(false);

  const createIntercept = useCreateIntercept();
  const queryClient = useQueryClient();

  const serviceNames = topology?.nodes.map(n => n.name) ?? [];

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!serviceName || !localPort) return;

    setStatusMsg('Injecting sidecar into pod...');
    setIsPolling(true);

    try {
      const created = await createIntercept.mutateAsync({
        sessionId,
        serviceName,
        namespace,
        localPort: parseInt(localPort, 10),
      });
      
      if (import.meta.env.VITE_MOCK === 'true') {
        setStatusMsg('');
        setIsPolling(false);
        onClose();
        return;
      }

      let attempts = 0;
      const poll = setInterval(async () => {
        attempts++;
        try {
          const data = await apiFetch<Intercept>(`/api/intercepts/${created.id}`);
          if (data.status === 'ACTIVE') {
            clearInterval(poll);
            queryClient.invalidateQueries({ queryKey: ['intercepts'] });
            setStatusMsg('');
            setIsPolling(false);
            onClose();
          }
        } catch (e) {
          // ignore error and retry
        }

        if (attempts >= 30) {
          clearInterval(poll);
          setStatusMsg('Error: Intercept timed out. The controller may be unreachable.');
          setIsPolling(false);
        }
      }, 1000);

    } catch (err: unknown) {
      setStatusMsg(`Error: ${err instanceof Error ? err.message : String(err)}`);
      setIsPolling(false);
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
              {createIntercept.isPending || isPolling ? (
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
              disabled={createIntercept.isPending || isPolling}
              id="submit-intercept-btn"
            >
              {createIntercept.isPending || isPolling ? 'Creating...' : 'Intercept'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
