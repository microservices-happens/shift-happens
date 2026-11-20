import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  createLeaveRequest,
  deleteLeaveRequest,
  getLeaveRequest,
  updateLeaveRequest,
} from '../../api/leaveRequests';
import { createLeaveApproval } from '../../api/leaveApprovals';
import { listEmployees } from '../../api/employees';
import { listLeaveTypes } from '../../api/leaveTypes';
import type {
  Employee,
  LeaveDecision,
  LeaveRequest,
  LeaveType,
  NewLeaveApproval,
  NewLeaveRequest,
} from '../../api/types';
import { ApiError } from '../../api/types';
import { useIdempotentAction } from '../../api/idempotency';
import { useAuth } from '../../auth/useAuth';
import { canWrite } from '../../auth/roles';
import { LeaveStatusPill } from './LeaveStatusPill';
import { isCancellable, isEditable, isSagaInFlight, isSettled } from './leaveStatus';
import { useBoundedPoll } from './useBoundedPoll';

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

function toForm(r: LeaveRequest): NewLeaveRequest {
  return {
    employeeId: r.employeeId,
    leaveTypeId: r.leaveTypeId,
    startDate: r.startDate,
    endDate: r.endDate,
    reason: r.reason,
  };
}

/**
 * Re-reads after a decision: the command answers 202 and the query role (Mongo read model) catches
 * up from events, and an approval only ends when the saga does (Scheduling's reply, or the saga
 * timeout, `SAGA_TIMEOUT`, default 5 min). 2 s x 45 = 90 s, then the page says so and offers a
 * manual re-check instead of polling on.
 */
const POLL = { intervalMs: 2000, maxAttempts: 45 };

export default function LeaveRequestFormPage() {
  const { id } = useParams<{ id: string }>();
  const isNew = id === undefined;
  const numericId = isNew ? null : Number(id);

  const navigate = useNavigate();
  const { user } = useAuth();
  const isEmployee = user?.roleName === 'Employee';
  // leave.yaml: PATCH, DELETE and POST /v1/leaveapprovals are Administrator/Manager only. An
  // employee can create a request for themself and view it.
  const mayReview = canWrite(user?.roleName);

  const [form, setForm] = useState<NewLeaveRequest>(() =>
    buildEmpty(user?.employeeId)
  );
  // One Idempotency-Key per submission of this form; a retry of the same data reuses it.
  const createAction = useIdempotentAction();
  // One key per decision (approve/reject with this comment) taken on this page.
  const decisionAction = useIdempotentAction();
  const [request, setRequest] = useState<LeaveRequest | null>(null);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [leaveTypes, setLeaveTypes] = useState<LeaveType[]>([]);
  const [loading, setLoading] = useState(!isNew);
  const [submitting, setSubmitting] = useState(false);
  const [deciding, setDeciding] = useState<LeaveDecision | null>(null);
  const [comment, setComment] = useState('');
  const [error, setError] = useState<string | null>(null);

  // A polling run is active while not null; a new number starts a new run.
  const [pollRun, setPollRun] = useState<number | null>(null);
  const runCounter = useRef(0);
  const startPolling = () => {
    runCounter.current += 1;
    setPollRun(runCounter.current);
  };

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
        /* the selects stay empty; saving reports the problem */
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
        setRequest(r);
        setForm(toForm(r));
        // Opened while the saga runs: follow it until it settles.
        if (isSagaInFlight(r.requestStatus)) {
          runCounter.current += 1;
          setPollRun(runCounter.current);
        }
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

  const pollExhausted = useBoundedPoll(
    pollRun,
    async () => {
      if (numericId === null) return true;
      const r = await getLeaveRequest(numericId);
      setRequest(r);
      setForm(toForm(r));
      if (!isSettled(r.requestStatus)) return false;
      setPollRun(null);
      return true;
    },
    POLL
  );

  const status = request?.requestStatus ?? null;
  const polling = pollRun !== null && !pollExhausted;
  const mayEdit = isNew || (mayReview && isEditable(status) && !polling);
  const mayDecide = mayReview && !isNew && status === 'PENDING' && !polling;

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
      if (isNew) await createAction.run(form, (key) => createLeaveRequest(form, key));
      else if (numericId !== null) await updateLeaveRequest(numericId, form);
      navigate('/leave-requests');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Save failed');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDecision(decision: LeaveDecision) {
    if (!mayDecide || numericId === null || user?.employeeId === undefined) return;
    const body: NewLeaveApproval = {
      leaveRequestId: numericId,
      approverEmployeeId: user.employeeId,
      decision,
      leaveComment: comment.trim() || null,
    };
    setError(null);
    setDeciding(decision);
    try {
      await decisionAction.run(body, (key) => createLeaveApproval(body, key));
      setComment('');
      // 202: REJECTED is final once the read model has it; APPROVED waits for the saga.
      startPolling();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Decision failed');
    } finally {
      setDeciding(null);
    }
  }

  async function handleCancelRequest() {
    if (isNew || numericId === null) return;
    if (!confirm('Cancel this leave request?')) return;
    try {
      await deleteLeaveRequest(numericId);
      navigate('/leave-requests');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Cancelling failed');
    }
  }

  if (loading) return <div className="page page-muted">Loading…</div>;

  return (
    <div className="page">
      <h1 className="page-title">
        {isNew ? 'New leave request' : 'Leave request'}
      </h1>
      {error && <div className="alert alert-error">{error}</div>}

      {!isNew && request && (
        <section className="card card--padded" style={{ marginBottom: '1rem' }}>
          <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
            <strong>Status</strong>
            <LeaveStatusPill status={status} />
          </div>

          {polling && status === 'PENDING' && (
            <p className="page-muted" role="status">
              Decision sent. Waiting for it to show up…
            </p>
          )}
          {isSagaInFlight(status) && !pollExhausted && (
            <p className="page-muted" role="status">
              Approved by a manager. The approval completes once Scheduling has released the
              employee&apos;s shifts in this period; this page updates by itself.
            </p>
          )}
          {pollExhausted && (
            <div className="alert alert-info" role="status" style={{ marginTop: '0.75rem' }}>
              {isSagaInFlight(status)
                ? 'The approval is still in progress. '
                : 'The decision has not shown up yet. '}
              The outcome arrives asynchronously.{' '}
              <button type="button" className="btn btn-sm" onClick={startPolling}>
                Check again
              </button>
            </div>
          )}

          {status === 'REJECTED' && (
            <div className="alert alert-error" style={{ marginTop: '0.75rem', marginBottom: 0 }}>
              <strong>Rejection reason:</strong>{' '}
              {request.rejectionReason || 'No reason given.'}
            </div>
          )}

          {mayDecide && (
            <div style={{ marginTop: '1rem' }}>
              <label className="form-field form-field--full">
                <span>Comment</span>
                <textarea
                  rows={2}
                  value={comment}
                  onChange={(e) => setComment(e.target.value)}
                  placeholder="Optional. Shown to the employee as the rejection reason when you reject."
                />
              </label>
              <div className="form-actions">
                <button
                  type="button"
                  className="btn btn-danger"
                  disabled={deciding !== null || user?.employeeId === undefined}
                  onClick={() => void handleDecision('REJECTED')}
                >
                  {deciding === 'REJECTED' ? 'Rejecting…' : 'Reject'}
                </button>
                <button
                  type="button"
                  className="btn btn-primary"
                  disabled={deciding !== null || user?.employeeId === undefined}
                  onClick={() => void handleDecision('APPROVED')}
                >
                  {deciding === 'APPROVED' ? 'Approving…' : 'Approve'}
                </button>
              </div>
            </div>
          )}
        </section>
      )}

      <form className="form-grid" onSubmit={handleSubmit}>
        <label className="form-field">
          <span>Employee</span>
          <select
            required
            value={form.employeeId}
            onChange={(e) => update('employeeId', Number(e.target.value))}
            disabled={isEmployee || !isNew}
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

        <div className="form-actions">
          {!isNew && mayReview && isCancellable(status) && !polling && (
            <button
              type="button"
              className="btn btn-danger"
              onClick={handleCancelRequest}
              style={{ marginRight: 'auto' }}
            >
              Cancel request
            </button>
          )}
          <button
            type="button"
            className="btn"
            onClick={() => navigate('/leave-requests')}
          >
            Back
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
