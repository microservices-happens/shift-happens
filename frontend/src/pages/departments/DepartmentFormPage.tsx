import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  createDepartment,
  getDepartment,
  updateDepartment,
} from '../../api/departments';
import type { NewDepartment } from '../../api/types';
import { ApiError } from '../../api/types';
import { useIdempotentAction } from '../../api/idempotency';
import { useAuth } from '../../auth/useAuth';
import { canWrite } from '../../auth/roles';

const EMPTY_FORM: NewDepartment = {
  departmentName: '',
  isActive: true,
};

export default function DepartmentFormPage() {
  const { id } = useParams<{ id: string }>();
  const isNew = id === 'new' || id === undefined;
  const numericId = isNew ? null : Number(id);

  const navigate = useNavigate();
  const { user } = useAuth();
  const mayWrite = canWrite(user?.roleName);

  const [form, setForm] = useState<NewDepartment>(EMPTY_FORM);
  // One Idempotency-Key per submission of this form; a retry of the same data reuses it.
  const createAction = useIdempotentAction();
  const [loading, setLoading] = useState(!isNew);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isNew || numericId === null) return;
    let cancelled = false;
    setLoading(true);
    getDepartment(numericId)
      .then((d) => {
        if (cancelled) return;
        setForm({ departmentName: d.departmentName, isActive: d.isActive });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setError(err instanceof ApiError ? err.message : 'Failed to load department');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [isNew, numericId]);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!mayWrite) return;
    setError(null);
    setSubmitting(true);
    try {
      if (isNew) await createAction.run(form, (key) => createDepartment(form, key));
      else if (numericId !== null) await updateDepartment(numericId, form);
      navigate('/departments');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Save failed');
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) return <div className="page page-muted">Loading…</div>;

  return (
    <div className="page">
      <h1 className="page-title">{isNew ? 'New department' : 'Edit department'}</h1>
      {error && <div className="alert alert-error">{error}</div>}
      <form className="form-grid" onSubmit={handleSubmit}>
        <label className="form-field">
          <span>Name</span>
          <input
            required
            value={form.departmentName ?? ''}
            onChange={(e) =>
              setForm((f) => ({ ...f, departmentName: e.target.value }))
            }
            disabled={!mayWrite}
          />
        </label>
        <label className="form-field">
          <span>Status</span>
          <select
            value={form.isActive ? 'active' : 'inactive'}
            onChange={(e) =>
              setForm((f) => ({ ...f, isActive: e.target.value === 'active' }))
            }
            disabled={!mayWrite}
          >
            <option value="active">Active</option>
            <option value="inactive">Inactive</option>
          </select>
        </label>
        <div className="form-actions">
          <button
            type="button"
            className="btn"
            onClick={() => navigate('/departments')}
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
