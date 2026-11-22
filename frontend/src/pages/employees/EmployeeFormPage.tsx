import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  createEmployee,
  getEmployee,
  updateEmployee,
} from '../../api/employees';
import { setPassword } from '../../api/accounts';
import { listWorkLocations } from '../../api/workLocations';
import { listJobRoles } from '../../api/jobRoles';
import {
  createEmployeeJobRole,
  deleteEmployeeJobRole,
  listEmployeeJobRoles,
} from '../../api/employeeJobRoles';
import type { EmployeeJobRole, JobRole, NewEmployee, RoleName, WorkLocation } from '../../api/types';
import { ApiError } from '../../api/types';
import { useIdempotentAction } from '../../api/idempotency';
import { useAuth } from '../../auth/useAuth';
import { canWrite, isAdmin } from '../../auth/roles';

const EMPTY_FORM: NewEmployee = {
  employeeNumber: '',
  firstName: '',
  lastName: '',
  email: '',
  userRole: 'Employee',
  phoneNumber: '',
  hireDate: null,
  employmentStatus: 'ACTIVE',
  primaryWorkLocationId: null,
};

function todayISO(): string {
  return new Date().toISOString().slice(0, 10);
}

export default function EmployeeFormPage() {
  const { id } = useParams<{ id: string }>();
  const isNew = id === 'new' || id === undefined;
  const numericId = isNew ? null : Number(id);

  const navigate = useNavigate();
  const { user } = useAuth();
  const mayWrite = canWrite(user?.roleName);
  // identity.yaml: Administrator, or the employee themself.
  const maySetPassword = isAdmin(user?.roleName) || (numericId !== null && user?.employeeId === numericId);

  const [form, setForm] = useState<NewEmployee>(EMPTY_FORM);
  // One Idempotency-Key per submission of this form; a retry of the same data reuses it.
  const createAction = useIdempotentAction();
  // Passwords belong to identity-service, not to the workforce Employee resource.
  const [newPassword, setNewPassword] = useState('');
  const [locations, setLocations] = useState<WorkLocation[]>([]);
  const [jobRoles, setJobRoles] = useState<JobRole[]>([]);
  const [employeeRoles, setEmployeeRoles] = useState<EmployeeJobRole[]>([]);
  const [roleToAdd, setRoleToAdd] = useState(0);
  const [roleExpiry, setRoleExpiry] = useState('');
  const [roleBusy, setRoleBusy] = useState(false);
  const [roleError, setRoleError] = useState<string | null>(null);
  const [loading, setLoading] = useState(!isNew);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([listWorkLocations(), listJobRoles()])
      .then(([ls, roles]) => {
        if (cancelled) return;
        setLocations(ls);
        setJobRoles(roles);
      })
      .catch(() => {
        /* dropdowns stay empty; surface via main error if needed */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (isNew || numericId === null) return;
    let cancelled = false;
    setLoading(true);
    getEmployee(numericId)
      .then((emp) => {
        if (cancelled) return;
        setForm({
          employeeNumber: emp.employeeNumber,
          firstName: emp.firstName,
          lastName: emp.lastName,
          email: emp.email,
          userRole: emp.userRole,
          phoneNumber: emp.phoneNumber,
          hireDate: emp.hireDate,
          employmentStatus: emp.employmentStatus,
          primaryWorkLocationId: emp.primaryWorkLocationId,
        });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof ApiError ? err.message : 'Failed to load employee');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isNew, numericId]);

  useEffect(() => {
    if (isNew || numericId === null) return;
    let cancelled = false;
    refreshEmployeeRoles(numericId).catch((err: unknown) => {
      if (!cancelled) setRoleError(err instanceof ApiError ? err.message : 'Failed to load job roles');
    });
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isNew, numericId]);

  async function refreshEmployeeRoles(id: number) {
    const rows = await listEmployeeJobRoles();
    setEmployeeRoles(rows.filter((row) => row.employeeId === id));
  }

  async function handleAddRole() {
    if (numericId === null || !roleToAdd) return;
    setRoleBusy(true);
    setRoleError(null);
    try {
      await createEmployeeJobRole(
        {
          employeeId: numericId,
          jobRoleId: roleToAdd,
          assignedDate: todayISO(),
          expiryDate: roleExpiry || null,
          proficiencyLevel: null,
        },
        crypto.randomUUID()
      );
      await refreshEmployeeRoles(numericId);
      setRoleToAdd(0);
      setRoleExpiry('');
    } catch (err) {
      setRoleError(err instanceof ApiError ? err.message : 'Could not add the job role');
    } finally {
      setRoleBusy(false);
    }
  }

  async function handleRemoveRole(id: number) {
    if (numericId === null) return;
    setRoleBusy(true);
    setRoleError(null);
    try {
      await deleteEmployeeJobRole(id);
      await refreshEmployeeRoles(numericId);
    } catch (err) {
      setRoleError(err instanceof ApiError ? err.message : 'Could not remove the job role');
    } finally {
      setRoleBusy(false);
    }
  }

  function update<K extends keyof NewEmployee>(key: K, value: NewEmployee[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!mayWrite) return;
    setError(null);
    setSubmitting(true);
    try {
      if (isNew) {
        const created = await createAction.run(form, (key) => createEmployee(form, key));
        // Stay on the employee so job roles and a password can be added right away.
        navigate(`/employees/${created.employeeId}`);
        return;
      }
      if (numericId !== null) {
        await updateEmployee(numericId, form);
        if (newPassword) await setPassword(numericId, newPassword);
      }
      navigate('/employees');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Save failed');
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <div className="page page-muted">Loading…</div>;

  return (
    <div className="page">
      <h1 className="page-title">{isNew ? 'New employee' : 'Edit employee'}</h1>
      {error && <div className="alert alert-error">{error}</div>}
      <form className="form-grid" onSubmit={handleSubmit}>
        <label className="form-field">
          <span>Employee number</span>
          <input
            value={form.employeeNumber ?? ''}
            onChange={(e) => update('employeeNumber', e.target.value)}
            disabled={!mayWrite}
          />
        </label>
        <label className="form-field">
          <span>First name</span>
          <input
            value={form.firstName ?? ''}
            onChange={(e) => update('firstName', e.target.value)}
            disabled={!mayWrite}
          />
        </label>
        <label className="form-field">
          <span>Last name</span>
          <input
            value={form.lastName ?? ''}
            onChange={(e) => update('lastName', e.target.value)}
            disabled={!mayWrite}
          />
        </label>
        <label className="form-field">
          <span>Email</span>
          <input
            type="email"
            required
            value={form.email ?? ''}
            onChange={(e) => update('email', e.target.value)}
            disabled={!mayWrite}
          />
        </label>
        <label className="form-field">
          <span>Phone</span>
          <input
            value={form.phoneNumber ?? ''}
            onChange={(e) => update('phoneNumber', e.target.value)}
            disabled={!mayWrite}
          />
        </label>
        <label className="form-field">
          <span>Role</span>
          <select
            value={form.userRole}
            onChange={(e) => update('userRole', e.target.value as RoleName)}
            disabled={!mayWrite}
          >
            <option value="Administrator">Administrator</option>
            <option value="Manager">Manager</option>
            <option value="Employee">Employee</option>
          </select>
        </label>
        <label className="form-field">
          <span>Primary work location</span>
          <select
            value={form.primaryWorkLocationId ?? ''}
            onChange={(e) =>
              update(
                'primaryWorkLocationId',
                e.target.value === '' ? null : Number(e.target.value)
              )
            }
            disabled={!mayWrite}
          >
            <option value="">—</option>
            {locations.map((l) => (
              <option key={l.workLocationId} value={l.workLocationId}>
                {l.locationName}
              </option>
            ))}
          </select>
        </label>
        <label className="form-field">
          <span>Hire date</span>
          <input
            type="date"
            value={form.hireDate ?? ''}
            onChange={(e) => update('hireDate', e.target.value || null)}
            disabled={!mayWrite}
          />
        </label>
        <label className="form-field">
          <span>Employment status</span>
          <select
            value={form.employmentStatus ?? ''}
            onChange={(e) => update('employmentStatus', e.target.value)}
            disabled={!mayWrite}
          >
            <option value="ACTIVE">Active</option>
            <option value="INACTIVE">Inactive</option>
            <option value="TERMINATED">Terminated</option>
          </select>
        </label>
        {/* identity-service creates the account from workforce.employee.created.v1, so a
            password can only be set once the employee exists (PUT /v1/auth/accounts/{id}/password). */}
        {!isNew && maySetPassword && (
          <label className="form-field">
            <span>New password (leave blank to keep)</span>
            <input
              type="password"
              value={newPassword}
              onChange={(e) => setNewPassword(e.target.value)}
              disabled={!mayWrite}
              autoComplete="new-password"
            />
          </label>
        )}

        {isNew && (
          <p className="page-muted" style={{ gridColumn: '1 / -1' }}>
            Save the employee first. The employee page then adds job roles, a contract and the login password.
          </p>
        )}

        {!isNew && (
          <div className="form-field" style={{ gridColumn: '1 / -1' }}>
            <span>Job roles</span>
            {roleError && <div className="alert alert-error">{roleError}</div>}
            {employeeRoles.length === 0 && (
              <p className="page-muted">
                No job roles yet. Scheduling can only assign this employee to a shift after a matching role
                is added.
              </p>
            )}
            {employeeRoles.map((row) => (
              <div key={row.employeeJobRoleId} className="flex flex-wrap items-center gap-2" style={{ marginBottom: 8 }}>
                <span className="name-cell-primary">
                  {jobRoles.find((role) => role.jobRoleId === row.jobRoleId)?.roleName ?? `Role #${row.jobRoleId}`}
                </span>
                <span className="name-cell-secondary">
                  from {row.assignedDate}
                  {row.expiryDate ? ` to ${row.expiryDate}` : ''}
                </span>
                {mayWrite && (
                  <button
                    type="button"
                    className="btn btn-sm btn-danger"
                    disabled={roleBusy}
                    onClick={() => void handleRemoveRole(row.employeeJobRoleId)}
                  >
                    Remove
                  </button>
                )}
              </div>
            ))}
            {mayWrite && (
              <div className="flex flex-wrap items-end gap-2">
                <label className="form-field" style={{ margin: 0 }}>
                  <span>Add role</span>
                  <select value={roleToAdd} onChange={(e) => setRoleToAdd(Number(e.target.value))}>
                    <option value={0} disabled>
                      Select…
                    </option>
                    {jobRoles
                      .filter((role) => !employeeRoles.some((row) => row.jobRoleId === role.jobRoleId))
                      .map((role) => (
                        <option key={role.jobRoleId} value={role.jobRoleId}>
                          {role.roleName}
                        </option>
                      ))}
                  </select>
                </label>
                <label className="form-field" style={{ margin: 0 }}>
                  <span>Expires (optional)</span>
                  <input type="date" value={roleExpiry} onChange={(e) => setRoleExpiry(e.target.value)} />
                </label>
                <button
                  type="button"
                  className="btn btn-sm"
                  disabled={roleBusy || !roleToAdd}
                  onClick={() => void handleAddRole()}
                >
                  Add job role
                </button>
              </div>
            )}
          </div>
        )}

        <div className="form-actions">
          <button
            type="button"
            className="btn"
            onClick={() => navigate('/employees')}
          >
            Cancel
          </button>
          {mayWrite && (
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? 'Saving…' : isNew ? 'Create' : 'Save changes'}
            </button>
          )}
        </div>
      </form>
    </div>
  );
}
