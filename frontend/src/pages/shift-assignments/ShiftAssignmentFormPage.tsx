import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@apollo/client/react';
import {
  createShiftAssignment,
  deleteShiftAssignment,
  updateShiftAssignment,
} from '../../api/shiftAssignments';
import { listEmployees } from '../../api/employees';
import type { Employee, NewShiftAssignment } from '../../api/types';
import { ApiError } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { canWrite } from '../../auth/roles';
import { graphqlErrorMessage } from '../../graphql/client';
import {
  ShiftByIdDocument,
  ShiftOptionsDocument,
  type AssignmentStatus,
  type ShiftSummaryFragment,
} from '../../graphql/generated';
import { fromLocalInput, scheduleWindow, toLocalInput } from '../../graphql/time';

// AssignmentStatus of contracts/graphql/scheduling.graphql, which scheduling-command also enforces.
const STATUSES: readonly AssignmentStatus[] = [
  'ASSIGNED',
  'APPROVED',
  'SWAPPED',
  'CANCELLED',
  'RELEASED_FOR_LEAVE',
];

function buildEmpty(): NewShiftAssignment {
  return {
    shiftId: 0,
    employeeId: 0,
    assignmentStatus: 'ASSIGNED',
    assignedDatetime: new Date().toISOString(),
    checkInDatetime: null,
    checkOutDatetime: null,
  };
}

function formatShiftLabel(s: ShiftSummaryFragment): string {
  const start = new Date(s.startDatetime);
  const when = Number.isNaN(start.getTime())
    ? ''
    : start.toLocaleString(undefined, {
        month: 'short',
        day: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      });
  return `${s.shiftName}${when ? ` · ${when}` : ''}`;
}

export default function ShiftAssignmentFormPage() {
  const { id } = useParams<{ id: string }>();
  const isNew = id === undefined;
  const numericId = isNew ? null : Number(id);

  // The contract has no query for one assignment, so it is read through its shift:
  // /shift-assignments/:id?shiftId=<shift> -> shift(shiftId) { assignments }.
  const [searchParams] = useSearchParams();
  const shiftIdParam = Number(searchParams.get('shiftId'));
  const parentShiftId = Number.isInteger(shiftIdParam) && shiftIdParam > 0 ? shiftIdParam : null;

  const navigate = useNavigate();
  const { user } = useAuth();
  const mayWrite = canWrite(user?.roleName);

  const [form, setForm] = useState<NewShiftAssignment>(buildEmpty);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [range] = useState(() => scheduleWindow());
  const shiftOptions = useQuery(ShiftOptionsDocument, { variables: range, skip: !mayWrite });
  const parent = useQuery(ShiftByIdDocument, {
    variables: { shiftId: parentShiftId ?? 0 },
    skip: isNew || parentShiftId === null,
    fetchPolicy: 'network-only',
  });
  const loadedShift = parent.data?.shift ?? null;
  const loaded = loadedShift?.assignments.find((a) => a.shiftAssignmentId === numericId) ?? null;

  // Copy the loaded assignment into the form once per result (state adjusted during render).
  const [formFrom, setFormFrom] = useState<typeof loaded>(null);
  if (loaded && loadedShift && loaded !== formFrom) {
    setFormFrom(loaded);
    setForm({
      shiftId: loadedShift.shiftId,
      employeeId: loaded.employee.employeeId,
      assignmentStatus: loaded.status,
      assignedDatetime: loaded.assignedDatetime,
      checkInDatetime: loaded.checkInDatetime,
      checkOutDatetime: loaded.checkOutDatetime,
    });
  }

  const loadError = isNew
    ? null
    : parentShiftId === null
      ? 'Open this assignment from the assignment list (the link carries its shift).'
      : parent.error
        ? graphqlErrorMessage(parent.error, 'Failed to load')
        : parent.data && !loaded
          ? 'Assignment not found (or not visible to you).'
          : null;
  const loading = !isNew && parent.loading && !parent.data;

  // The window's shifts, plus the assignment's own shift if it lies outside the window.
  const shifts = useMemo(() => {
    const options = shiftOptions.data?.shifts ?? [];
    if (loadedShift && !options.some((s) => s.shiftId === loadedShift.shiftId)) {
      return [loadedShift, ...options];
    }
    return options;
  }, [shiftOptions.data, loadedShift]);

  useEffect(() => {
    let cancelled = false;
    listEmployees()
      .then((es) => {
        if (cancelled) return;
        setEmployees(es);
        setForm((f) => ({ ...f, employeeId: f.employeeId || es[0]?.employeeId || 0 }));
      })
      .catch(() => {
        /* handled by main error state if needed */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Preselect the first shift for a new assignment once the options are there.
  if (isNew && form.shiftId === 0 && shifts.length > 0) {
    setForm((f) => ({ ...f, shiftId: shifts[0].shiftId }));
  }

  function update<K extends keyof NewShiftAssignment>(
    key: K,
    value: NewShiftAssignment[K]
  ) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  function updateDatetime(
    key: 'assignedDatetime' | 'checkInDatetime' | 'checkOutDatetime',
    raw: string
  ) {
    setForm((prev) => ({ ...prev, [key]: fromLocalInput(raw) }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!mayWrite) return;
    setError(null);
    setSubmitting(true);
    try {
      if (isNew) await createShiftAssignment(form);
      else if (numericId !== null) await updateShiftAssignment(numericId, form);
      navigate('/shift-assignments');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Save failed');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete() {
    if (isNew || numericId === null) return;
    if (!confirm('Delete this assignment?')) return;
    try {
      await deleteShiftAssignment(numericId);
      navigate('/shift-assignments');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Delete failed');
    }
  }

  if (loading) return <div className="page page-muted">Loading…</div>;

  return (
    <div className="page">
      <h1 className="page-title">
        {isNew ? 'New shift assignment' : 'Edit shift assignment'}
      </h1>
      {(error ?? loadError) && <div className="alert alert-error">{error ?? loadError}</div>}
      <form className="form-grid" onSubmit={handleSubmit}>
        <label className="form-field">
          <span>Employee</span>
          <select
            required
            value={form.employeeId}
            onChange={(e) => update('employeeId', Number(e.target.value))}
            disabled={!mayWrite}
          >
            <option value={0} disabled>
              Select…
            </option>
            {employees.map((e) => (
              <option key={e.employeeId} value={e.employeeId}>
                {e.firstName} {e.lastName}
                {e.email ? ` · ${e.email}` : ''}
              </option>
            ))}
          </select>
        </label>

        <label className="form-field">
          <span>Shift</span>
          <select
            required
            value={form.shiftId}
            onChange={(e) => update('shiftId', Number(e.target.value))}
            disabled={!mayWrite}
          >
            <option value={0} disabled>
              Select…
            </option>
            {shifts.map((s) => (
              <option key={s.shiftId} value={s.shiftId}>
                {formatShiftLabel(s)}
              </option>
            ))}
          </select>
        </label>

        <label className="form-field">
          <span>Status</span>
          <select
            value={form.assignmentStatus ?? ''}
            onChange={(e) => update('assignmentStatus', e.target.value)}
            disabled={!mayWrite}
          >
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s
                  .toLowerCase()
                  .split('_')
                  .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
                  .join(' ')}
              </option>
            ))}
          </select>
        </label>

        <label className="form-field">
          <span>Assigned at</span>
          <input
            type="datetime-local"
            value={toLocalInput(form.assignedDatetime)}
            onChange={(e) => updateDatetime('assignedDatetime', e.target.value)}
            disabled={!mayWrite}
          />
        </label>

        <label className="form-field">
          <span>Check-in</span>
          <input
            type="datetime-local"
            value={toLocalInput(form.checkInDatetime)}
            onChange={(e) => updateDatetime('checkInDatetime', e.target.value)}
            disabled={!mayWrite}
          />
        </label>

        <label className="form-field">
          <span>Check-out</span>
          <input
            type="datetime-local"
            value={toLocalInput(form.checkOutDatetime)}
            onChange={(e) => updateDatetime('checkOutDatetime', e.target.value)}
            disabled={!mayWrite}
          />
        </label>

        <div className="form-actions">
          {!isNew && mayWrite && (
            <button
              type="button"
              className="btn btn-danger"
              onClick={handleDelete}
              style={{ marginRight: 'auto' }}
            >
              Delete
            </button>
          )}
          <button
            type="button"
            className="btn"
            onClick={() => navigate('/shift-assignments')}
          >
            Cancel
          </button>
          {mayWrite && (
            <button
              type="submit"
              className="btn btn-primary"
              disabled={submitting}
            >
              {submitting ? 'Saving…' : isNew ? 'Create' : 'Save changes'}
            </button>
          )}
        </div>
      </form>
    </div>
  );
}
