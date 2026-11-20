import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { useQuery } from '@apollo/client/react';
import { createShift, deleteShift, updateShift } from '../../api/shifts';
import { listDepartments } from '../../api/departments';
import { listWorkLocations } from '../../api/workLocations';
import type { Department, NewShift, WorkLocation } from '../../api/types';
import { ApiError } from '../../api/types';
import { useIdempotentAction } from '../../api/idempotency';
import { useAuth } from '../../auth/useAuth';
import { canWrite } from '../../auth/roles';
import { graphqlErrorMessage } from '../../graphql/client';
import { ShiftByIdDocument, type ShiftStatus } from '../../graphql/generated';
import { fromLocalInput, toLocalInput } from '../../graphql/time';

// ShiftStatus of contracts/graphql/scheduling.graphql, which scheduling-command also enforces.
const STATUSES: readonly ShiftStatus[] = ['PLANNED', 'PUBLISHED', 'LOCKED', 'CANCELLED'];

function localToDateTime(dateISO: string, time: string): string {
  return fromLocalInput(`${dateISO}T${time}`) ?? `${dateISO}T${time}:00Z`;
}

function buildEmpty(dateISO: string | null): NewShift {
  const baseDate = dateISO ?? new Date().toISOString().slice(0, 10);
  return {
    departmentId: 0,
    workLocationId: 0,
    shiftName: '',
    startDatetime: localToDateTime(baseDate, '09:00'),
    endDatetime: localToDateTime(baseDate, '17:00'),
    shiftStatus: 'PLANNED',
  };
}

export default function ShiftFormPage() {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const presetDate = searchParams.get('date');
  const isNew = id === undefined;
  const numericId = isNew ? null : Number(id);

  const navigate = useNavigate();
  const { user } = useAuth();
  const mayWrite = canWrite(user?.roleName);

  const [form, setForm] = useState<NewShift>(() => buildEmpty(presetDate));
  // One Idempotency-Key per submission of this form; a retry of the same data reuses it.
  const createAction = useIdempotentAction();
  const [departments, setDepartments] = useState<Department[]>([]);
  const [locations, setLocations] = useState<WorkLocation[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([listDepartments(), listWorkLocations()])
      .then(([ds, ls]) => {
        if (cancelled) return;
        setDepartments(ds);
        setLocations(ls);
        setForm((f) => ({
          ...f,
          departmentId: f.departmentId || ds[0]?.departmentId || 0,
          workLocationId: f.workLocationId || ls[0]?.workLocationId || 0,
        }));
      })
      .catch(() => {
        /* surfaced via main error if needed */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Reads are GraphQL: shift(shiftId) on scheduling-query.
  const { data, loading, error: queryError } = useQuery(ShiftByIdDocument, {
    variables: { shiftId: numericId ?? 0 },
    skip: isNew || numericId === null,
    fetchPolicy: 'network-only',
  });

  // Copy the loaded shift into the form once per result (state adjusted during render, not in an effect).
  const loaded = data?.shift ?? null;
  const [formFrom, setFormFrom] = useState<typeof loaded>(null);
  if (loaded && loaded !== formFrom) {
    setFormFrom(loaded);
    setForm({
      departmentId: loaded.departmentId,
      workLocationId: loaded.workLocationId,
      shiftName: loaded.shiftName,
      startDatetime: loaded.startDatetime,
      endDatetime: loaded.endDatetime,
      shiftStatus: loaded.status,
    });
  }

  const loadError = queryError
    ? graphqlErrorMessage(queryError, 'Failed to load shift')
    : data && !data.shift
      ? 'Shift not found (or not visible to you).'
      : null;

  function update<K extends keyof NewShift>(key: K, value: NewShift[K]) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (!mayWrite) return;
    setError(null);
    setSubmitting(true);
    try {
      if (isNew) await createAction.run(form, (key) => createShift(form, key));
      else if (numericId !== null) await updateShift(numericId, form);
      navigate('/shifts');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Save failed');
    } finally {
      setSubmitting(false);
    }
  }

  async function handleDelete() {
    if (isNew || numericId === null) return;
    if (!confirm('Delete this shift?')) return;
    try {
      await deleteShift(numericId);
      navigate('/shifts');
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Delete failed');
    }
  }

  if (loading && !data) return <div className="page page-muted">Loading…</div>;

  return (
    <div className="page">
      <h1 className="page-title">{isNew ? 'New shift' : 'Edit shift'}</h1>
      {(error ?? loadError) && <div className="alert alert-error">{error ?? loadError}</div>}
      <form className="form-grid" onSubmit={handleSubmit}>
        <label className="form-field">
          <span>Shift name</span>
          <input
            value={form.shiftName ?? ''}
            onChange={(e) => update('shiftName', e.target.value)}
            disabled={!mayWrite}
          />
        </label>
        <label className="form-field">
          <span>Status</span>
          <select
            value={form.shiftStatus ?? ''}
            onChange={(e) => update('shiftStatus', e.target.value)}
            disabled={!mayWrite}
          >
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s.charAt(0) + s.slice(1).toLowerCase()}
              </option>
            ))}
          </select>
        </label>
        <label className="form-field">
          <span>Department</span>
          <select
            required
            value={form.departmentId}
            onChange={(e) => update('departmentId', Number(e.target.value))}
            disabled={!mayWrite}
          >
            <option value={0} disabled>
              Select…
            </option>
            {departments.map((d) => (
              <option key={d.departmentId} value={d.departmentId}>
                {d.departmentName}
              </option>
            ))}
          </select>
        </label>
        <label className="form-field">
          <span>Work location</span>
          <select
            required
            value={form.workLocationId}
            onChange={(e) => update('workLocationId', Number(e.target.value))}
            disabled={!mayWrite}
          >
            <option value={0} disabled>
              Select…
            </option>
            {locations.map((l) => (
              <option key={l.workLocationId} value={l.workLocationId}>
                {l.locationName}
              </option>
            ))}
          </select>
        </label>
        <label className="form-field">
          <span>Start</span>
          <input
            type="datetime-local"
            required
            value={toLocalInput(form.startDatetime)}
            onChange={(e) => update('startDatetime', fromLocalInput(e.target.value) ?? '')}
            disabled={!mayWrite}
          />
        </label>
        <label className="form-field">
          <span>End</span>
          <input
            type="datetime-local"
            required
            value={toLocalInput(form.endDatetime)}
            onChange={(e) => update('endDatetime', fromLocalInput(e.target.value) ?? '')}
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
          <button type="button" className="btn" onClick={() => navigate('/shifts')}>
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
