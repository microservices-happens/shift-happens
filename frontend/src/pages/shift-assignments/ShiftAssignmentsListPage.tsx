import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@apollo/client/react';
import { deleteShiftAssignment } from '../../api/shiftAssignments';
import { listDepartments } from '../../api/departments';
import { listEmployees } from '../../api/employees';
import { createShiftSwap } from '../../api/shiftSwaps';
import type { Department, Employee } from '../../api/types';
import { ApiError } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { canWrite } from '../../auth/roles';
import { graphqlErrorMessage } from '../../graphql/client';
import {
  ShiftsWithAssignmentsDocument,
  type ShiftsWithAssignmentsQuery,
} from '../../graphql/generated';
import { FUTURE_WEEKS, PAST_WEEKS, scheduleWindow } from '../../graphql/time';

type ListShift = ShiftsWithAssignmentsQuery['shifts'][number];
type ListEmployee = ListShift['assignments'][number]['employee'];

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

function employeeInitials(e: ListEmployee): string {
  const f = e.firstName?.[0] ?? '';
  const l = e.lastName?.[0] ?? '';
  return (f + l).toUpperCase() || '?';
}

function statusPillClass(status: string | null): string {
  switch (status) {
    case 'APPROVED':
      return 'pill pill-success';
    case 'SWAPPED':
      return 'pill pill-brand';
    case 'CANCELLED':
    case 'RELEASED_FOR_LEAVE':
      return 'pill pill-danger';
    default:
      return 'pill pill-neutral';
  }
}

function formatStatus(status: string | null): string {
  if (!status) return 'Unknown';
  return status
    .toLowerCase()
    .split('_')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}

function formatRange(start: string | null, end: string | null): string {
  if (!start) return '—';
  const s = new Date(start);
  if (Number.isNaN(s.getTime())) return '—';
  const date = s.toLocaleDateString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
  });
  const sTime = s.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  if (!end) return `${date} · ${sTime}`;
  const e = new Date(end);
  const eTime = e.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return `${date} · ${sTime} – ${eTime}`;
}

export default function ShiftAssignmentsListPage() {
  const { user } = useAuth();
  const mayWrite = canWrite(user?.roleName);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [swapFor, setSwapFor] = useState<
    { assignmentId: number; fromEmployeeId: number; fromLabel: string } | null
  >(null);
  const [swapToId, setSwapToId] = useState(0);
  const [swapReason, setSwapReason] = useState('');
  const [swapBusy, setSwapBusy] = useState(false);
  const [swapError, setSwapError] = useState<string | null>(null);
  const [swapNotice, setSwapNotice] = useState<string | null>(null);

  // There is no "all assignments" query in the contract: assignments are read through
  // shifts(from, to) { assignments }, over a fixed window around the current week.
  const [range] = useState(() => scheduleWindow());
  const { data, error: queryError, refetch } = useQuery(ShiftsWithAssignmentsDocument, {
    variables: range,
  });
  const [deleteError, setDeleteError] = useState<string | null>(null);
  const error = queryError
    ? graphqlErrorMessage(queryError, 'Failed to load shift assignments')
    : deleteError;

  const assignments = useMemo(
    () =>
      data
        ? data.shifts
            .flatMap((shift) => shift.assignments.map((assignment) => ({ shift, assignment })))
            .sort((x, y) => x.shift.startDatetime.localeCompare(y.shift.startDatetime))
        : null,
    [data]
  );

  const [departments, setDepartments] = useState<Department[]>([]);
  useEffect(() => {
    let cancelled = false;
    listDepartments()
      .then((d) => {
        if (!cancelled) setDepartments(d);
      })
      .catch(() => {
        /* department names are decoration */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const deptById = useMemo(() => {
    const m = new Map<number, Department>();
    for (const d of departments) m.set(d.departmentId, d);
    return m;
  }, [departments]);

  async function handleDelete(id: number, label: string) {
    if (!confirm(`Delete assignment for "${label}"?`)) return;
    setDeleteError(null);
    try {
      await deleteShiftAssignment(id);
      void refetch();
    } catch (err) {
      setDeleteError(err instanceof ApiError ? err.message : 'Delete failed');
    }
  }

  useEffect(() => {
    listEmployees()
      .then(setEmployees)
      .catch(() => {
        /* the swap form reports that it needs colleagues */
      });
  }, []);

  async function handleSwapRequest() {
    if (!swapFor || !swapToId) return;
    setSwapBusy(true);
    setSwapError(null);
    setSwapNotice(null);
    try {
      const swap = await createShiftSwap(
        {
          originalShiftAssignmentId: swapFor.assignmentId,
          employeeFromId: swapFor.fromEmployeeId,
          employeeToId: swapToId,
          reason: swapReason.trim() || null,
        },
        crypto.randomUUID()
      );
      setSwapNotice(
        `Swap request ${swap.shiftSwapId} sent. A manager decides it on the Swap Requests page.`
      );
      setSwapFor(null);
      setSwapReason('');
      setSwapToId(0);
    } catch (err) {
      setSwapError(err instanceof ApiError ? err.message : 'Swap request failed');
    } finally {
      setSwapBusy(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">Shift Assignments</h1>
          <p className="page-subtitle">
            Who's scheduled where, from {PAST_WEEKS} weeks back to {FUTURE_WEEKS} weeks ahead.
          </p>
        </div>
        {mayWrite && (
          <Link to="/shift-assignments/new" className="btn btn-primary">
            New assignment
          </Link>
        )}
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {swapNotice && <div className="alert">{swapNotice}</div>}
      {swapFor && (
        <div className="card mb-4 p-4">
          <h2 className="mb-2 text-lg font-semibold">Request a swap for {swapFor.fromLabel}</h2>
          {swapError && <div className="alert alert-error">{swapError}</div>}
          <div className="flex flex-wrap items-end gap-3">
            <label className="form-field" style={{ margin: 0 }}>
              <span>Colleague</span>
              <select value={swapToId} onChange={(e) => setSwapToId(Number(e.target.value))}>
                <option value={0} disabled>
                  Select…
                </option>
                {employees
                  .filter(
                    (employee) =>
                      employee.employmentStatus === 'ACTIVE' &&
                      employee.employeeId !== swapFor.fromEmployeeId
                  )
                  .map((employee) => (
                    <option key={employee.employeeId} value={employee.employeeId}>
                      {employee.firstName} {employee.lastName} (#{employee.employeeId})
                    </option>
                  ))}
              </select>
            </label>
            <label className="form-field" style={{ margin: 0, flex: '1 1 240px' }}>
              <span>Reason (optional)</span>
              <input value={swapReason} onChange={(e) => setSwapReason(e.target.value)} />
            </label>
            <button
              type="button"
              className="btn btn-primary"
              disabled={swapBusy || !swapToId}
              onClick={() => void handleSwapRequest()}
            >
              {swapBusy ? 'Sending…' : 'Send request'}
            </button>
            <button
              type="button"
              className="btn"
              onClick={() => {
                setSwapFor(null);
                setSwapError(null);
              }}
            >
              Cancel
            </button>
          </div>
          <p className="page-muted mt-2 text-sm">
            The colleague must be active and hold the job role the shift requires; Scheduling validates it.
          </p>
        </div>
      )}
      {!assignments && !error && <div className="page-muted">Loading…</div>}

      {assignments && (
        <>
          <div className="data-table-toolbar">
            <span className="data-table-count">
              {assignments.length}{' '}
              {assignments.length === 1 ? 'assignment' : 'assignments'}
            </span>
          </div>

          <table className="data-table">
            <thead>
              <tr>
                <th>Employee</th>
                <th>Shift</th>
                <th>When</th>
                <th>Status</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {assignments.length === 0 && (
                <tr>
                  <td colSpan={5} className="page-muted">
                    No shift assignments yet.
                  </td>
                </tr>
              )}
              {assignments.map(({ shift, assignment: a }) => {
                const emp = a.employee;
                const dept = deptById.get(shift.departmentId);
                const empLabel = `${emp.firstName} ${emp.lastName}`.trim();
                const editPath = `/shift-assignments/${a.shiftAssignmentId}?shiftId=${shift.shiftId}`;
                return (
                  <tr key={a.shiftAssignmentId}>
                    <td>
                      <Link
                        to={editPath}
                        className="name-cell hover:no-underline"
                      >
                        <span
                          className="avatar"
                          style={{ background: avatarColor(emp.employeeId) }}
                        >
                          {employeeInitials(emp)}
                        </span>
                        <span>
                          <span className="name-cell-primary">{empLabel}</span>
                          <span className="name-cell-secondary">
                            Employee #{emp.employeeId}
                          </span>
                        </span>
                      </Link>
                    </td>
                    <td>
                      <div className="name-cell-primary">
                        {shift.shiftName}
                      </div>
                      <div className="name-cell-secondary">
                        {dept?.departmentName ?? ''}
                      </div>
                    </td>
                    <td className="text-slate-500">
                      {formatRange(shift.startDatetime, shift.endDatetime)}
                    </td>
                    <td>
                      <span className={statusPillClass(a.status)}>
                        <span className="pill-dot" />
                        {formatStatus(a.status)}
                      </span>
                    </td>
                    <td className="data-table-actions">
                      {(mayWrite || user?.employeeId === emp.employeeId) && (
                        <button
                          type="button"
                          className="btn btn-sm"
                          onClick={() => {
                            setSwapFor({
                              assignmentId: a.shiftAssignmentId,
                              fromEmployeeId: emp.employeeId,
                              fromLabel: empLabel,
                            });
                            setSwapToId(0);
                            setSwapError(null);
                            setSwapNotice(null);
                          }}
                        >
                          Request swap
                        </button>
                      )}
                      {mayWrite && (
                        <>
                          <Link
                            to={editPath}
                            className="btn btn-sm"
                          >
                            Edit
                          </Link>
                          <button
                            type="button"
                            className="btn btn-sm btn-danger"
                            onClick={() =>
                              handleDelete(a.shiftAssignmentId, empLabel)
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
