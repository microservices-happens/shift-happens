import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  deleteLeaveRequest,
  listLeaveRequests,
} from '../../api/leaveRequests';
import { listEmployees } from '../../api/employees';
import { listLeaveTypes } from '../../api/leaveTypes';
import type {
  Employee,
  LeaveRequest,
  LeaveType,
} from '../../api/types';
import { ApiError } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { canWrite } from '../../auth/roles';

const AVATAR_COLORS = [
  '#6366f1',
  '#ec4899',
  '#f97316',
  '#14b8a6',
  '#8b5cf6',
  '#ef4444',
  '#f59e0b',
  '#10b981',
  '#0ea5e9',
  '#d946ef',
];

function avatarColor(id: number): string {
  return AVATAR_COLORS[Math.abs(id) % AVATAR_COLORS.length];
}

function employeeInitials(e: Employee | undefined): string {
  if (!e) return '?';
  const f = e.firstName?.[0] ?? '';
  const l = e.lastName?.[0] ?? '';
  return (f + l).toUpperCase() || '?';
}

function statusPillClass(status: string | null | undefined): string {
  const s = (status ?? '').toLowerCase();
  if (s === 'approved') return 'pill pill-success';
  if (s === 'rejected' || s === 'denied' || s === 'cancelled')
    return 'pill pill-danger';
  if (s === 'pending') return 'pill pill-brand';
  return 'pill pill-neutral';
}

function formatStatus(status: string | null | undefined): string {
  if (!status) return 'Unknown';
  return status.charAt(0).toUpperCase() + status.slice(1).toLowerCase();
}

function formatRange(start: string | null, end: string | null): string {
  if (!start) return '—';
  const s = new Date(start);
  if (Number.isNaN(s.getTime())) return '—';
  const sStr = s.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
  if (!end) return sStr;
  const e = new Date(end);
  const eStr = e.toLocaleDateString(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  });
  return sStr === eStr ? sStr : `${sStr} – ${eStr}`;
}

function dayCount(start: string | null, end: string | null): number {
  if (!start || !end) return 0;
  const s = new Date(start).getTime();
  const e = new Date(end).getTime();
  if (Number.isNaN(s) || Number.isNaN(e) || e < s) return 0;
  return Math.round((e - s) / 86_400_000) + 1;
}

export default function LeaveRequestsListPage() {
  const { user } = useAuth();
  const mayWrite = canWrite(user?.roleName);
  const isEmployee = user?.roleName === 'Employee';

  const [requests, setRequests] = useState<LeaveRequest[] | null>(null);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [leaveTypes, setLeaveTypes] = useState<LeaveType[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([listLeaveRequests(), listEmployees(), listLeaveTypes()])
      .then(([r, e, t]) => {
        if (cancelled) return;
        setRequests(r);
        setEmployees(e);
        setLeaveTypes(t);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(
          err instanceof ApiError ? err.message : 'Failed to load leave requests'
        );
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const empById = useMemo(() => {
    const m = new Map<number, Employee>();
    for (const e of employees) m.set(e.employeeId, e);
    return m;
  }, [employees]);

  const typeById = useMemo(() => {
    const m = new Map<number, LeaveType>();
    for (const t of leaveTypes) m.set(t.leaveTypeId, t);
    return m;
  }, [leaveTypes]);

  const visible = useMemo(() => {
    if (!requests) return null;
    if (isEmployee && user?.employeeId !== undefined) {
      return requests.filter((r) => r.employeeId === user.employeeId);
    }
    return requests;
  }, [requests, isEmployee, user?.employeeId]);

  async function handleDelete(id: number, label: string) {
    if (!confirm(`Delete leave request for "${label}"?`)) return;
    try {
      await deleteLeaveRequest(id);
      setRequests((prev) => prev?.filter((r) => r.leaveRequestId !== id) ?? null);
    } catch (err) {
      alert(err instanceof ApiError ? err.message : 'Delete failed');
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">Leave Requests</h1>
          <p className="page-subtitle">
            {isEmployee
              ? 'Track the time off you have requested.'
              : 'Review and manage time-off requests from your team.'}
          </p>
        </div>
        <Link to="/leave-requests/new" className="btn btn-primary">
          New request
        </Link>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {!visible && !error && <div className="page-muted">Loading…</div>}

      {visible && (
        <>
          <div className="data-table-toolbar">
            <span className="data-table-count">
              {visible.length} {visible.length === 1 ? 'request' : 'requests'}
            </span>
          </div>

          <table className="data-table">
            <thead>
              <tr>
                <th>Employee</th>
                <th>Type</th>
                <th>Dates</th>
                <th>Days</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {visible.length === 0 && (
                <tr>
                  <td colSpan={6} className="page-muted">
                    No leave requests yet.
                  </td>
                </tr>
              )}
              {visible.map((r) => {
                const emp = empById.get(r.employeeId);
                const type = typeById.get(r.leaveTypeId);
                const empLabel = emp
                  ? `${emp.firstName ?? ''} ${emp.lastName ?? ''}`.trim()
                  : `#${r.employeeId}`;
                const days = dayCount(r.startDate, r.endDate);
                const canModify = mayWrite || r.employeeId === user?.employeeId;
                return (
                  <tr key={r.leaveRequestId}>
                    <td>
                      <Link
                        to={`/leave-requests/${r.leaveRequestId}`}
                        className="name-cell hover:no-underline"
                      >
                        <span
                          className="avatar"
                          style={{ background: avatarColor(r.employeeId) }}
                        >
                          {employeeInitials(emp)}
                        </span>
                        <span>
                          <span className="name-cell-primary">{empLabel}</span>
                          <span className="name-cell-secondary">
                            {emp?.email ?? ''}
                          </span>
                        </span>
                      </Link>
                    </td>
                    <td>{type?.leaveTypeName ?? `#${r.leaveTypeId}`}</td>
                    <td className="text-slate-500">
                      {formatRange(r.startDate, r.endDate)}
                    </td>
                    <td className="text-slate-500">
                      {days > 0 ? `${days} ${days === 1 ? 'day' : 'days'}` : '—'}
                    </td>
                    <td>
                      <span className={statusPillClass(r.requestStatus)}>
                        <span className="pill-dot" />
                        {formatStatus(r.requestStatus)}
                      </span>
                    </td>
                    <td className="data-table-actions">
                      {canModify && (
                        <>
                          <Link
                            to={`/leave-requests/${r.leaveRequestId}`}
                            className="btn btn-sm"
                          >
                            {mayWrite ? 'Review' : 'View'}
                          </Link>
                          <button
                            type="button"
                            className="btn btn-sm btn-danger"
                            onClick={() =>
                              handleDelete(r.leaveRequestId, empLabel)
                            }
                          >
                            Delete
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </>
      )}
    </div>
  );
}
