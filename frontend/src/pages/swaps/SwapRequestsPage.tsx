import { useState } from 'react';
import { useQuery } from '@apollo/client/react';
import { createShiftSwapApproval } from '../../api/shiftSwaps';
import { ApiError } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { canWrite } from '../../auth/roles';
import { graphqlErrorMessage } from '../../graphql/client';
import {
  SwapRequestsDocument,
  type SwapRequestsQuery,
  type SwapStatus,
} from '../../graphql/generated';

type SwapRow = SwapRequestsQuery['swapRequests'][number];
type Filter = SwapStatus | 'ALL';

function person(name: SwapRow['from'] | null): string {
  return name ? `${name.firstName} ${name.lastName}`.trim() : '—';
}

function when(value: string | null): string {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

/**
 * The manager queue for shift swaps. A request is only a proposal until a manager approves it: the
 * approval moves the assignment in Scheduling in one local transaction and publishes
 * scheduling.swap.approved.v1; a rejection leaves the assignment where it is.
 */
export default function SwapRequestsPage() {
  const { user } = useAuth();
  const allowed = canWrite(user?.roleName);
  const [filter, setFilter] = useState<Filter>('PENDING');
  const [comments, setComments] = useState<Record<number, string>>({});
  const [busy, setBusy] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const { data, loading, error: queryError, refetch } = useQuery(SwapRequestsDocument, {
    variables: { status: filter === 'ALL' ? undefined : filter },
    skip: !allowed,
    fetchPolicy: 'network-only',
    pollInterval: 15_000,
  });

  const errorMessage = queryError ? graphqlErrorMessage(queryError, 'Failed to load swap requests') : error;

  async function decide(row: SwapRow, decision: 'APPROVED' | 'REJECTED') {
    if (!user) return;
    setBusy(row.shiftSwapId);
    setError(null);
    setNotice(null);
    try {
      await createShiftSwapApproval(
        {
          shiftSwapId: row.shiftSwapId,
          approverEmployeeId: user.employeeId,
          decision,
          shiftSwapComment: comments[row.shiftSwapId]?.trim() || null,
        },
        crypto.randomUUID()
      );
      setNotice(`${decision === 'APPROVED' ? 'Approved' : 'Rejected'} swap ${row.shiftSwapId}.`);
      setComments((current) => ({ ...current, [row.shiftSwapId]: '' }));
      await refetch();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Decision failed');
    } finally {
      setBusy(null);
    }
  }

  if (!allowed) {
    return (
      <div className="page">
        <div className="page-header">
          <h1 className="page-title">Swap requests</h1>
        </div>
        <div className="alert alert-error">Only an Administrator or Manager may decide a swap.</div>
      </div>
    );
  }

  const rows = data?.swapRequests ?? [];

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">Swap requests</h1>
          <p className="page-subtitle">
            Employees propose a colleague for one of their assignments; a manager approves or rejects it.
          </p>
        </div>
        <label className="form-field" style={{ margin: 0 }}>
          <span>Status</span>
          <select value={filter} onChange={(e) => setFilter(e.target.value as Filter)}>
            <option value="PENDING">Pending</option>
            <option value="APPROVED">Approved</option>
            <option value="REJECTED">Rejected</option>
            <option value="ALL">All</option>
          </select>
        </label>
      </div>

      {errorMessage && <div className="alert alert-error">{errorMessage}</div>}
      {notice && <div className="alert">{notice}</div>}
      {loading && !data && <div className="page-muted">Loading…</div>}

      {data && (
        <table className="data-table">
          <thead>
            <tr>
              <th>Shift</th>
              <th>From → to</th>
              <th>Reason</th>
              <th>Requested</th>
              <th>Status</th>
              <th>Decision</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="page-muted">
                  No swap requests with this status.
                </td>
              </tr>
            )}
            {rows.map((row) => (
              <tr key={row.shiftSwapId}>
                <td>
                  <div className="name-cell-primary">{row.assignment.shift.shiftName}</div>
                  <div className="name-cell-secondary">{when(row.assignment.shift.startDatetime)}</div>
                </td>
                <td>
                  {person(row.from)} → {person(row.to)}
                </td>
                <td>{row.reason || '—'}</td>
                <td>{when(row.requestDatetime)}</td>
                <td>
                  <span
                    className={
                      row.status === 'APPROVED'
                        ? 'pill pill-success'
                        : row.status === 'REJECTED'
                          ? 'pill pill-danger'
                          : 'pill pill-neutral'
                    }
                  >
                    <span className="pill-dot" />
                    {row.status}
                  </span>
                </td>
                <td>
                  {row.status === 'PENDING' ? (
                    <div className="flex flex-wrap items-center gap-2">
                      <input
                        aria-label={`Comment for swap ${row.shiftSwapId}`}
                        placeholder="Comment (optional)"
                        value={comments[row.shiftSwapId] ?? ''}
                        onChange={(e) =>
                          setComments((current) => ({ ...current, [row.shiftSwapId]: e.target.value }))
                        }
                        disabled={busy === row.shiftSwapId}
                      />
                      <button
                        type="button"
                        className="btn btn-sm btn-primary"
                        disabled={busy === row.shiftSwapId}
                        onClick={() => void decide(row, 'APPROVED')}
                      >
                        Approve
                      </button>
                      <button
                        type="button"
                        className="btn btn-sm btn-danger"
                        disabled={busy === row.shiftSwapId}
                        onClick={() => void decide(row, 'REJECTED')}
                      >
                        Reject
                      </button>
                    </div>
                  ) : (
                    <div>
                      <div>{row.decidedBy ? person(row.decidedBy) : '—'}</div>
                      <div className="name-cell-secondary">
                        {row.decisionComment || '—'} · {when(row.decisionDatetime)}
                      </div>
                    </div>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
