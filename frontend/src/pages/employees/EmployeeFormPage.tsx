import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  createEmployee,
  getEmployee,
  updateEmployee,
} from '../../api/employees';
import { setPassword } from '../../api/accounts';
import { listWorkLocations } from '../../api/workLocations';
import type { NewEmployee, RoleName, WorkLocation } from '../../api/types';
import { ApiError } from '../../api/types';
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
  // Passwords belong to identity-service, not to the workforce Employee resource.
  const [newPassword, setNewPassword] = useState('');
  const [locations, setLocations] = useState<WorkLocation[]>([]);
  const [loading, setLoading] = useState(!isNew);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    listWorkLocations()
      .then((ls) => {
        if (cancelled) return;
        setLocations(ls);
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
        await createEmployee(form);
      } else if (numericId !== null) {
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
