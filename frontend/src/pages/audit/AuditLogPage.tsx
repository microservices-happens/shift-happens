import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { searchAuditLogs, type AuditFilters } from '../../api/auditLogs';
import type { AuditEntry } from '../../api/types';
import { ApiError } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { isAdmin } from '../../auth/roles';

function formatInstant(value?: string): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/** An ISO instant for the service, or undefined for "no bound". datetime-local has no zone: use local time. */
function toInstant(localValue: string): string | undefined {
  if (!localValue) return undefined;
  const date = new Date(localValue);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

/**
 * The append-only audit log (contracts/openapi/audit.yaml). Administrator only; the service enforces
 * that with a 403, and the nav item is hidden for everyone else. Read-only by design: there is no
 * button that changes an entry.
 */
export default function AuditLogPage() {
  const { user } = useAuth();
  const allowed = isAdmin(user?.roleName);

  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [eventType, setEventType] = useState('');
  const [aggregateId, setAggregateId] = useState('');
  const [correlationId, setCorrelationId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const load = useCallback(async (filters: AuditFilters) => {
    setError(null);
    try {
      setEntries(await searchAuditLogs(filters));
    } catch (err) {
      setEntries([]);
      setError(err instanceof ApiError ? err.message : 'Failed to load the audit log');
    }
  }, []);

  useEffect(() => {
    if (allowed) void load({});
  }, [allowed, load]);

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    void load({
      eventType: eventType.trim() || undefined,
      aggregateId: aggregateId.trim() === '' ? undefined : Number(aggregateId),
      correlationId: correlationId.trim() || undefined,
      from: toInstant(from),
      to: toInstant(to),
    });
  }

  if (!allowed) {
    return (
      <div className="page">
        <div className="page-header">
          <div>
            <h1 className="page-title">Audit log</h1>
            <p className="page-subtitle">Only an Administrator may read the audit log.</p>
          </div>
        </div>
        <div className="alert alert-error">Your role does not include the audit log (403 from the service).</div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">Audit log</h1>
          <p className="page-subtitle">
            Every business event, appended unchanged and searchable by event type, aggregate or
            correlation id. Filter by one correlation id to see a whole saga.
          </p>
        </div>
        <span className="data-table-count">{entries ? `${entries.length} entries` : ''}</span>
      </div>

      <form className="data-table-toolbar" onSubmit={handleSubmit}>
        <input
          aria-label="Event type"
          placeholder="scheduling.swap.approved.v1"
          value={eventType}
          onChange={(e) => setEventType(e.target.value)}
        />
        <input
          aria-label="Aggregate id"
          placeholder="Aggregate id"
          inputMode="numeric"
          value={aggregateId}
          onChange={(e) => setAggregateId(e.target.value)}
        />
        <input
          aria-label="Correlation id"
          placeholder="Correlation id"
          value={correlationId}
          onChange={(e) => setCorrelationId(e.target.value)}
        />
        <input
          aria-label="From"
          type="datetime-local"
          value={from}
          onChange={(e) => setFrom(e.target.value)}
        />
        <input aria-label="To" type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
        <button type="submit" className="btn btn-primary">
          Search
        </button>
      </form>

      {error && <div className="alert alert-error">{error}</div>}
      {!entries && !error && <div className="page-muted">Loading…</div>}

      {entries && (
        <table className="data-table">
          <thead>
            <tr>
              <th>Occurred</th>
              <th>Event</th>
              <th>Producer</th>
              <th>Aggregate</th>
              <th>Correlation</th>
              <th>Envelope</th>
            </tr>
          </thead>
          <tbody>
            {entries.length === 0 && (
              <tr>
                <td colSpan={6} className="page-muted">
                  No entries match these filters.
                </td>
              </tr>
            )}
            {entries.map((entry) => (
              <tr key={entry.auditId}>
                <td>{formatInstant(entry.occurredAt)}</td>
                <td>
                  <span className="name-cell-primary">{entry.eventType}</span>
                </td>
                <td>{entry.producer}</td>
                <td>{entry.aggregateId ?? '—'}</td>
                <td className="font-mono text-xs">{entry.correlationId ?? '—'}</td>
                <td>
                  <details>
                    <summary className="cursor-pointer text-xs">view</summary>
                    <pre className="max-h-64 overflow-auto text-xs">
                      {JSON.stringify(entry.envelope, null, 2)}
                    </pre>
                  </details>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
