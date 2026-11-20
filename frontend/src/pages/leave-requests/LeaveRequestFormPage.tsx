import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  createLeaveRequest,
  deleteLeaveRequest,
  getLeaveRequest,
  updateLeaveRequest,
} from '../../api/leaveRequests';
import { listEmployees } from '../../api/employees';
import { listLeaveTypes } from '../../api/leaveTypes';
import type {
  Employee,
  LeaveRequestStatus,
  LeaveType,
  NewLeaveRequest,
} from '../../api/types';
import { ApiError } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { canWrite } from '../../auth/roles';

function formatStatus(status: LeaveRequestStatus | null): string {
  if (!status) return 'Unknown';
  const words = status.toLowerCase().replace(/_/g, ' ');
  return words.charAt(0).toUpperCase() + words.slice(1);
}

function todayISO(): string {
  return new Date().toISOString().slice(0, 10);
}

function buildEmpty(employeeId: number | undefined): NewLeaveRequest {
  return {
    employeeId: employeeId ?? 0,
    leaveTypeId: 0,
    startDate: todayISO(),
    endDate: todayISO(),
    reason: '',
  };
}

export default function LeaveRequestFormPage() {
  const { id } = useParams<{ id: string }>();
  const isNew = id === undefined;
  const numericId = isNew ? null : Number(id);

  const navigate = useNavigate();
  const { user } = useAuth();
  const isEmployee = user?.roleName === 'Employee';
  const mayReview = canWrite(user?.roleName);

  const [form, setForm] = useState<NewLeaveRequest>(() =>
    buildEmpty(user?.employeeId)
  );
  const [originalEmployeeId, setOriginalEmployeeId] = useState<number | null>(
    null
  );
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [leaveTypes, setLeaveTypes] = useState<LeaveType[]>([]);
  const [status, setStatus] = useState<LeaveRequestStatus | null>(null);
  const [loading, setLoading] = useState(!isNew);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([listEmployees(), listLeaveTypes()])
      .then(([es, ts]) => {
        if (cancelled) return;
        setEmployees(es);
        setLeaveTypes(ts);
        setForm((f) => ({
          ...f,
          employeeId:
            f.employeeId || user?.employeeId || es[0]?.employeeId || 0,
          leaveTypeId: f.leaveTypeId || ts[0]?.leaveTypeId || 0,
        }));
      })
      .catch(() => {
        /* handled below if needed */
      });
    return () => {
      cancelled = true;
    };
  }, [user?.employeeId]);

  useEffect(() => {
    if (isNew || numericId === null) return;
    let cancelled = false;
    setLoading(true);
    getLeaveRequest(numericId)
      .then((r) => {
        if (cancelled) return;
        setOriginalEmployeeId(r.employeeId);
        setForm({
          employeeId: r.employeeId,
          leaveTypeId: r.leaveTypeId,
          startDate: r.startDate,
          endDate: r.endDate,
          reason: r.reason,
        });
        setStatus(r.requestStatus ?? null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof ApiError ? err.message : 'Failed to load');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isNew, numericId]);

  const isOwner =
    isNew || originalEmployeeId === null
      ? true
      : originalEmployeeId === user?.employeeId;
  const mayEdit = mayReview || isOwner;

  function update<K extends keyof NewLeaveRequest>(
    key: K,
    value: NewLeaveRequest[K]
  ) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!mayEdit) return;
    setError(null);
    setSubmitting(true);
    try {
      if (isNew) await createLeaveRequest(form);
      else if (numericId !== null) await updateLeaveRequest(numericId, form);
      navigate('/leave-requests');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Save failed');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete() {
    if (isNew || numericId === null) return;
    if (!confirm('Delete this leave request?')) return;
    try {
      await deleteLeaveRequest(numericId);
      navigate('/leave-requests');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Delete failed');
    }
  }

  if (loading) return <div className="page page-muted">Loading…</div>;

  return (
    <div className="page">
      <h1 className="page-title">
        {isNew ? 'New leave request' : 'Leave request'}
      </h1>
      {error && <div className="alert alert-error">{error}</div>}
      <form className="form-grid" onSubmit={handleSubmit}>
        <label className="form-field">
          <span>Employee</span>
          <select
            required
            value={form.employeeId}
            onChange={(e) => update('employeeId', Number(e.target.value))}
            disabled={isEmployee || !mayEdit}
          >
            <option value={0} disabled>
              Select…
            </option>
            {employees.map((emp) => (
              <option key={emp.employeeId} value={emp.employeeId}>
                {emp.firstName} {emp.lastName}
                {emp.email ? ` · ${emp.email}` : ''}
              </option>
            ))}
          </select>
        </label>

        <label className="form-field">
          <span>Leave type</span>
          <select
            required
            value={form.leaveTypeId}
            onChange={(e) => update('leaveTypeId', Number(e.target.value))}
            disabled={!mayEdit}
          >
            <option value={0} disabled>
              Select…
            </option>
            {leaveTypes.map((t) => (
              <option key={t.leaveTypeId} value={t.leaveTypeId}>
                {t.leaveTypeName}
                {t.isPaidLeave ? ' (paid)' : ''}
              </option>
            ))}
          </select>
        </label>

        <label className="form-field">
          <span>Start date</span>
          <input
            type="date"
            required
            value={form.startDate ?? ''}
            onChange={(e) => update('startDate', e.target.value)}
            disabled={!mayEdit}
          />
        </label>

        <label className="form-field">
          <span>End date</span>
          <input
            type="date"
            required
            value={form.endDate ?? ''}
            onChange={(e) => update('endDate', e.target.value)}
            disabled={!mayEdit}
          />
        </label>

        <label className="form-field form-field--full">
          <span>Reason</span>
          <textarea
            rows={3}
            value={form.reason ?? ''}
            onChange={(e) => update('reason', e.target.value)}
            disabled={!mayEdit}
            placeholder="Optional note for the approver"
          />
        </label>

        {/* requestStatus is read-only in leave.yaml: the leave-approval saga sets it after a
            decision is posted to /v1/leaveapprovals (approval UI: P5.4). */}
        {!isNew && (
          <label className="form-field">
            <span>Status</span>
            <input value={formatStatus(status)} disabled readOnly />
          </label>
        )}

        <div className="form-actions">
          {!isNew && mayEdit && (
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
            onClick={() => navigate('/leave-requests')}
          >
            Cancel
          </button>
          {mayEdit && (
            <button
              type="submit"
              className="btn btn-primary"
              disabled={submitting}
            >
              {submitting ? 'Saving…' : isNew ? 'Submit request' : 'Save changes'}
            </button>
          )}
        </div>
      </form>
    </div>
  );
}
