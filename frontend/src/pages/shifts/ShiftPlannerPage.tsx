import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@apollo/client/react';
import { listDepartments } from '../../api/departments';
import type { Department } from '../../api/types';
import { useAuth } from '../../auth/useAuth';
import { canWrite } from '../../auth/roles';
import { graphqlErrorMessage } from '../../graphql/client';
import {
  ShiftsWithAssignmentsDocument,
  type AssignmentStatus,
  type ShiftsWithAssignmentsQuery,
} from '../../graphql/generated';
import { addDays, startOfWeek, weekWindow } from '../../graphql/time';
import { UnderstaffedShiftsPanel } from './UnderstaffedShiftsPanel';

type WeekShift = ShiftsWithAssignmentsQuery['shifts'][number];
type WeekAssignment = WeekShift['assignments'][number];
type PlannerEmployee = WeekAssignment['employee'];
type PlannerCell = { day: Date; shift: WeekShift; assignment: WeekAssignment };

/** The statuses that occupy an employee (scheduling's AssignmentStatus.isActive). */
const ACTIVE_STATUSES: ReadonlySet<AssignmentStatus> = new Set(['ASSIGNED', 'APPROVED', 'SWAPPED']);

const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

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

const DEPT_COLORS = [
  '#2563eb',
  '#f59e0b',
  '#10b981',
  '#ef4444',
  '#8b5cf6',
  '#0ea5e9',
  '#ec4899',
  '#14b8a6',
];

function isSameDay(a: Date, b: Date): boolean {
  return (
    a.getFullYear() === b.getFullYear() &&
    a.getMonth() === b.getMonth() &&
    a.getDate() === b.getDate()
  );
}

function formatTime(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatWeekRange(start: Date, end: Date): string {
  const sameMonth = start.getMonth() === end.getMonth();
  const startStr = start.toLocaleDateString(undefined, {
    day: 'numeric',
    month: sameMonth ? undefined : 'short',
  });
  const endStr = end.toLocaleDateString(undefined, {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
  });
  return `${startStr} – ${endStr}`;
}

function isoWeekNumber(date: Date): number {
  const tmp = new Date(
    Date.UTC(date.getFullYear(), date.getMonth(), date.getDate())
  );
  const dayNum = tmp.getUTCDay() || 7;
  tmp.setUTCDate(tmp.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(tmp.getUTCFullYear(), 0, 1));
  return Math.ceil(((tmp.getTime() - yearStart.getTime()) / 86400000 + 1) / 7);
}

function initials(e: PlannerEmployee): string {
  const f = e.firstName?.[0] ?? '';
  const l = e.lastName?.[0] ?? '';
  return (f + l).toUpperCase() || '?';
}

function avatarColor(id: number): string {
  return AVATAR_COLORS[Math.abs(id) % AVATAR_COLORS.length];
}

function deptColor(id: number): string {
  return DEPT_COLORS[Math.abs(id) % DEPT_COLORS.length];
}

function hoursBetween(start: string | null, end: string | null): number {
  if (!start || !end) return 0;
  const s = new Date(start).getTime();
  const e = new Date(end).getTime();
  if (Number.isNaN(s) || Number.isNaN(e)) return 0;
  return Math.max(0, (e - s) / 3_600_000);
}

function formatHours(h: number): string {
  if (h === 0) return '0h';
  const rounded = Math.round(h * 10) / 10;
  return Number.isInteger(rounded) ? `${rounded}h` : `${rounded.toFixed(1)}h`;
}

export default function ShiftPlannerPage() {
  const { user } = useAuth();
  const mayWrite = canWrite(user?.roleName);
  const navigate = useNavigate();

  const [weekStart, setWeekStart] = useState<Date>(() => startOfWeek(new Date()));
  // shifts(from, to) with nested assignments and employees: one round trip per week. An Employee
  // only gets the shifts they are assigned to (scheduling-query decides).
  const variables = useMemo(() => weekWindow(weekStart), [weekStart]);
  const { data, loading, error: queryError } = useQuery(ShiftsWithAssignmentsDocument, { variables });
  const shifts = useMemo(() => data?.shifts ?? [], [data]);
  const error = queryError ? graphqlErrorMessage(queryError, 'Failed to load planner') : null;

  const [departments, setDepartments] = useState<Department[]>([]);
  useEffect(() => {
    let cancelled = false;
    listDepartments()
      .then((ds) => {
        if (!cancelled) setDepartments(ds);
      })
      .catch(() => {
        /* department names are decoration; the planner still renders */
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const weekDays = useMemo(
    () => Array.from({ length: 7 }, (_, i) => addDays(weekStart, i)),
    [weekStart]
  );
  const weekEnd = weekDays[6];

  const departmentById = useMemo(() => {
    const map = new Map<number, Department>();
    departments.forEach((d) => map.set(d.departmentId, d));
    return map;
  }, [departments]);

  const rows = useMemo(() => {
    const byEmployee = new Map<number, { employee: PlannerEmployee; cells: PlannerCell[] }>();
    for (const shift of shifts) {
      const day = weekDays.find((d) => isSameDay(d, new Date(shift.startDatetime)));
      if (!day) continue;
      for (const assignment of shift.assignments) {
        if (!ACTIVE_STATUSES.has(assignment.status)) continue;
        const { employee } = assignment;
        const row = byEmployee.get(employee.employeeId) ?? { employee, cells: [] };
        row.cells.push({ day, shift, assignment });
        byEmployee.set(employee.employeeId, row);
      }
    }
    return [...byEmployee.values()]
      .sort((a, b) =>
        `${a.employee.firstName} ${a.employee.lastName}`.localeCompare(
          `${b.employee.firstName} ${b.employee.lastName}`
        )
      )
      .map(({ employee, cells }) => ({
        employee,
        cells,
        totalHours: cells.reduce(
          (sum, c) => sum + hoursBetween(c.shift.startDatetime, c.shift.endDatetime),
          0
        ),
      }));
  }, [shifts, weekDays]);

  /** Shifts in the week that nobody is actively assigned to (not visible in the team rows). */
  const openShifts = useMemo(
    () =>
      shifts.filter(
        (s) =>
          s.status !== 'CANCELLED' && !s.assignments.some((a) => ACTIVE_STATUSES.has(a.status))
      ),
    [shifts]
  );

  const weekStats = useMemo(() => {
    const visible = shifts.filter((s) => s.status !== 'CANCELLED');
    return {
      shifts: visible.length,
      hours: visible.reduce((sum, s) => sum + hoursBetween(s.startDatetime, s.endDatetime), 0),
      understaffed: visible.filter((s) => s.isUnderstaffed).length,
    };
  }, [shifts]);

  const today = new Date();
  const weekNum = isoWeekNumber(weekStart);

  function goPrev() {
    setWeekStart((w) => addDays(w, -7));
  }
  function goNext() {
    setWeekStart((w) => addDays(w, 7));
  }
  function goToday() {
    setWeekStart(startOfWeek(new Date()));
  }

  function onBlockClick(shiftId: number) {
    navigate(`/shifts/${shiftId}`);
  }

  function onCellClick(employeeId: number, day: Date) {
    if (!mayWrite) return;
    const params = new URLSearchParams({
      employeeId: String(employeeId),
      date: day.toISOString().slice(0, 10),
    });
    navigate(`/shifts/new?${params.toString()}`);
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1 className="page-title">Shifts</h1>
        {mayWrite && (
          <Link to="/shifts/new" className="btn btn-primary">
            New shift
          </Link>
        )}
      </div>

      <div className="planner-toolbar">
        <div className="planner-nav">
          <button type="button" className="planner-nav-btn" onClick={goPrev} aria-label="Previous week">
            ←
          </button>
          <button type="button" className="planner-nav-btn planner-nav-btn--text" onClick={goToday}>
            Today
          </button>
          <button type="button" className="planner-nav-btn" onClick={goNext} aria-label="Next week">
            →
          </button>
          <span className="planner-nav-label">
            {formatWeekRange(weekStart, weekEnd)}
          </span>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}
      {loading && !data && <div className="card card--padded page-muted">Loading…</div>}

      {data && (
        <div className="planner-card">
          <div className="planner-weekbar">
            <div className="planner-weekbar-left">
              <span className="planner-week-badge">Week {weekNum}</span>
              <span className="planner-week-range">
                {formatWeekRange(weekStart, weekEnd)}
              </span>
            </div>
            <div className="planner-week-stats">
              <span className="planner-week-stat">
                {weekStats.shifts} {weekStats.shifts === 1 ? 'shift' : 'shifts'}
              </span>
              <span className="planner-week-stat">
                {formatHours(weekStats.hours)}
              </span>
              {mayWrite && weekStats.understaffed > 0 && (
                <span className="planner-week-stat">
                  {weekStats.understaffed} understaffed
                </span>
              )}
            </div>
          </div>

          <div className="planner">
            <div className="planner-cell planner-header planner-header--team">
              Team
            </div>
            {weekDays.map((d) => {
              const todayCol = isSameDay(d, today);
              return (
                <div
                  key={d.toISOString()}
                  className={
                    'planner-cell planner-header' +
                    (todayCol ? ' planner-header--today' : '')
                  }
                >
                  <span className="planner-header-label">
                    {DAY_LABELS[(d.getDay() + 6) % 7]}
                  </span>
                  <span className="planner-header-day">{d.getDate()}</span>
                </div>
              );
            })}

            {rows.map(({ employee, cells, totalHours }) => (
              <PlannerRow
                key={employee.employeeId}
                employee={employee}
                cells={cells}
                totalHours={totalHours}
                weekDays={weekDays}
                today={today}
                mayWrite={mayWrite}
                departmentById={departmentById}
                onBlockClick={onBlockClick}
                onCellClick={onCellClick}
              />
            ))}
          </div>

          {rows.length === 0 && openShifts.length === 0 && (
            <div className="planner-empty">
              No shifts scheduled this week.
            </div>
          )}

          {openShifts.length > 0 && (
            <div className="card card--padded" style={{ marginTop: '1rem' }}>
              <h2 className="page-section-title">Not staffed yet</h2>
              <ul>
                {openShifts.map((s) => (
                  <li key={s.shiftId}>
                    <Link to={`/shifts/${s.shiftId}`}>{s.shiftName}</Link>{' '}
                    <span className="page-muted">
                      {new Date(s.startDatetime).toLocaleDateString(undefined, {
                        weekday: 'short',
                        day: 'numeric',
                        month: 'short',
                      })}{' '}
                      · {formatTime(s.startDatetime)} – {formatTime(s.endDatetime)}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {mayWrite && <UnderstaffedShiftsPanel from={variables.from} to={variables.to} />}
    </div>
  );
}

interface PlannerRowProps {
  employee: PlannerEmployee;
  cells: PlannerCell[];
  totalHours: number;
  weekDays: Date[];
  today: Date;
  mayWrite: boolean;
  departmentById: Map<number, Department>;
  onBlockClick: (shiftId: number) => void;
  onCellClick: (employeeId: number, day: Date) => void;
}

function PlannerRow({
  employee,
  cells,
  totalHours,
  weekDays,
  today,
  mayWrite,
  departmentById,
  onBlockClick,
  onCellClick,
}: PlannerRowProps) {
  const cellsByDay = new Map<string, PlannerRowProps['cells']>();
  for (const c of cells) {
    const key = c.day.toDateString();
    const list = cellsByDay.get(key) ?? [];
    list.push(c);
    cellsByDay.set(key, list);
  }

  return (
    <>
      <div className="planner-cell planner-employee">
        <div
          className="planner-avatar"
          style={{ background: avatarColor(employee.employeeId) }}
        >
          {initials(employee)}
        </div>
        <div className="planner-employee-meta">
          <span className="planner-employee-name">
            {employee.firstName} {employee.lastName}
          </span>
          <span className="planner-employee-hours">
            {formatHours(totalHours)} this week
          </span>
        </div>
      </div>
      {weekDays.map((d) => {
        const items = cellsByDay.get(d.toDateString()) ?? [];
        const todayCol = isSameDay(d, today);
        const cellClass =
          'planner-cell' +
          (mayWrite ? ' planner-row-add' : '') +
          (todayCol ? ' planner-cell--today' : '');
        return (
          <div
            key={d.toISOString()}
            className={cellClass}
            onClick={(e) => {
              if (e.target !== e.currentTarget) return;
              onCellClick(employee.employeeId, d);
            }}
          >
            {items.map(({ shift, assignment }) => {
              const statusClass = ` planner-block--status-${shift.status.toLowerCase()}`;
              const dept = departmentById.get(shift.departmentId);
              const accent = deptColor(shift.departmentId);
              return (
                <div
                  key={assignment.shiftAssignmentId}
                  className={`planner-block${statusClass}`}
                  style={{ ['--block-accent' as string]: accent }}
                  onClick={(e) => {
                    e.stopPropagation();
                    onBlockClick(shift.shiftId);
                  }}
                >
                  <div className="planner-block-time">
                    {formatTime(shift.startDatetime)} –{' '}
                    {formatTime(shift.endDatetime)}
                  </div>
                  <div className="planner-block-name">{shift.shiftName}</div>
                  {shift.isUnderstaffed && mayWrite && (
                    <span className="pill pill-danger">Understaffed</span>
                  )}
                  {dept && (
                    <div className="planner-block-dept">
                      <span className="planner-block-dept-dot" />
                      {dept.departmentName}
                    </div>
                  )}
                </div>
              );
            })}
            {mayWrite && items.length === 0 && (
              <span className="planner-empty-hint">+</span>
            )}
          </div>
        );
      })}
    </>
  );
}
