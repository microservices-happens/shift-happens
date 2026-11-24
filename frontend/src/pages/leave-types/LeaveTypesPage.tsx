import { useEffect, useState, type FormEvent } from 'react';
import { listEmployees } from '../../api/employees';
import { createLedgerEntry } from '../../api/leaveLedger';
import { createLeaveType, listLeaveTypes } from '../../api/leaveTypes';
import type { Employee, LeaveType } from '../../api/types';
import { ApiError } from '../../api/types';
import { useIdempotentAction } from '../../api/idempotency';
import { useAuth } from '../../auth/useAuth';
import { canWrite } from '../../auth/roles';

/**
 * Leave types and the balances that make an approval possible. A leave type alone has no balance: the
 * immutable ledger is the source of truth, so a manager grants days here (an ACCRUAL entry) before an
 * employee can take leave.
 */
export default function LeaveTypesPage() {
  const { user } = useAuth();
  const allowed = canWrite(user?.roleName);

  const [types, setTypes] = useState<LeaveType[] | null>(null);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [requiresApproval, setRequiresApproval] = useState(true);
  const [isPaidLeave, setIsPaidLeave] = useState(true);
  const [grantEmployeeId, setGrantEmployeeId] = useState(0);
  const [grantTypeId, setGrantTypeId] = useState(0);
  const [grantDays, setGrantDays] = useState(25);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const typeAction = useIdempotentAction();
  const grantAction = useIdempotentAction();

  async function reload(focusTypeId?: number) {
    const [ts, es] = await Promise.all([listLeaveTypes(), listEmployees()]);
    setTypes(ts);
    setEmployees(es);
    setGrantTypeId((current) => focusTypeId ?? (current || ts[0]?.leaveTypeId || 0));
    setGrantEmployeeId(
      (current) => current || es.find((employee) => employee.employmentStatus === 'ACTIVE')?.employeeId || 0
    );
  }

  useEffect(() => {
    if (!allowed) return;
    let cancelled = false;
    reload().catch((err: unknown) => {
      if (!cancelled) setError(err instanceof ApiError ? err.message : 'Failed to load leave types');
    });
    return () => {
      cancelled = true;
    };
  }, [allowed]);

  async function handleCreateType(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setNotice(null);
    setBusy(true);
    const data = {
      leaveTypeName: name.trim(),
      leaveTypeDescription: description.trim() || null,
      requiresApproval,
      isPaidLeave,
    };
    try {
      const created = await typeAction.run(data, (key) => createLeaveType(data, key));
      // The GET is served by the query role's Mongo read model, which catches up from events. Show the
      // new type immediately instead of waiting for the projection; a later page load sees it too.
      setTypes((current) => (current ? [...current, created] : [created]));
      setGrantTypeId(created.leaveTypeId);
      setNotice(`Leave type "${created.leaveTypeName}" created. Grant days below before approving leave.`);
      setName('');
      setDescription('');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Create failed');
    } finally {
      setBusy(false);
    }
  }

  async function handleGrant(event: FormEvent) {
    event.preventDefault();
    setError(null);
    setNotice(null);
    if (!grantEmployeeId || !grantTypeId || grantDays <= 0) {
      setError('Choose an employee and a leave type, and enter a positive number of days.');
      return;
    }
    setBusy(true);
    const entry = {
      employeeId: grantEmployeeId,
      leaveTypeId: grantTypeId,
      changeAmountDays: grantDays,
      transactionType: 'ACCRUAL',
      referenceEntityType: 'MANUAL',
      referenceEntityId: null,
    };
    try {
      await grantAction.run(entry, (key) => createLedgerEntry(entry, key));
      const type = types?.find((t) => t.leaveTypeId === grantTypeId);
      setNotice(`${grantDays} day(s) granted${type ? ` of ${type.leaveTypeName}` : ''}.`);
      await reload();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Grant failed');
    } finally {
      setBusy(false);
    }
  }

  if (!allowed) {
    return (
      <div className="page">
        <div className="page-header">
          <h1 className="page-title">Leave types</h1>
        </div>
        <div className="alert alert-error">Only an Administrator or Manager may manage leave types.</div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">Leave types &amp; balances</h1>
          <p className="page-subtitle">
            The types an employee can request, and the accruals that make an approval possible. The ledger
            is append-only: a correction is a new entry with the opposite amount.
          </p>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {notice && <div className="alert">{notice}</div>}

      <div className="grid gap-6 lg:grid-cols-2">
        <form className="card p-4" onSubmit={handleCreateType}>
          <h2 className="mb-2 text-lg font-semibold">New leave type</h2>
          <label className="form-field">
            <span>Name</span>
            <input required value={name} onChange={(e) => setName(e.target.value)} placeholder="Vacation" />
          </label>
          <label className="form-field">
            <span>Description</span>
            <input value={description} onChange={(e) => setDescription(e.target.value)} />
          </label>
          <label className="mb-2 flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={requiresApproval}
              onChange={(e) => setRequiresApproval(e.target.checked)}
            />
            Requires approval
          </label>
          <label className="mb-4 flex items-center gap-2 text-sm">
            <input type="checkbox" checked={isPaidLeave} onChange={(e) => setIsPaidLeave(e.target.checked)} />
            Paid leave
          </label>
          <button type="submit" className="btn btn-primary" disabled={busy || !name.trim()}>
            Create leave type
          </button>
        </form>

        <form className="card p-4" onSubmit={handleGrant}>
          <h2 className="mb-2 text-lg font-semibold">Grant days (accrual)</h2>
          <label className="form-field">
            <span>Employee</span>
            <select value={grantEmployeeId} onChange={(e) => setGrantEmployeeId(Number(e.target.value))} required>
              <option value={0} disabled>
                Select…
              </option>
              {employees
                .filter((employee) => employee.employmentStatus === 'ACTIVE')
                .map((employee) => (
                  <option key={employee.employeeId} value={employee.employeeId}>
                    {employee.firstName} {employee.lastName} (#{employee.employeeId})
                  </option>
                ))}
            </select>
          </label>
          <label className="form-field">
            <span>Leave type</span>
            <select value={grantTypeId} onChange={(e) => setGrantTypeId(Number(e.target.value))} required>
              <option value={0} disabled>
                Select…
              </option>
              {(types ?? []).map((type) => (
                <option key={type.leaveTypeId} value={type.leaveTypeId}>
                  {type.leaveTypeName}
                </option>
              ))}
            </select>
          </label>
          <label className="form-field">
            <span>Days</span>
            <input
              type="number"
              min={1}
              step={0.5}
              value={grantDays}
              onChange={(e) => setGrantDays(Number(e.target.value))}
              required
            />
          </label>
          <p className="page-muted mb-3 text-sm">
            The ledger entry is created now; leave-service stamps the entry with its own timestamp.
          </p>
          <button type="submit" className="btn btn-primary" disabled={busy || !grantTypeId || !grantEmployeeId}>
            Grant days
          </button>
        </form>
      </div>

      <h2 className="mb-2 mt-8 text-lg font-semibold">Configured types</h2>
      {!types && !error && <div className="page-muted">Loading…</div>}
      {types && (
        <table className="data-table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Description</th>
              <th>Approval</th>
              <th>Paid</th>
            </tr>
          </thead>
          <tbody>
            {types.length === 0 && (
              <tr>
                <td colSpan={4} className="page-muted">
                  No leave types yet. Create one, then grant days.
                </td>
              </tr>
            )}
            {types.map((type) => (
              <tr key={type.leaveTypeId}>
                <td className="name-cell-primary">{type.leaveTypeName}</td>
                <td>{type.leaveTypeDescription ?? '—'}</td>
                <td>{type.requiresApproval ? 'Required' : 'Automatic'}</td>
                <td>{type.isPaidLeave ? 'Yes' : 'No'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
