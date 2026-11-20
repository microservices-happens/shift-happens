import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery } from '@apollo/client/react';
import { useAuth } from '../auth/useAuth';
import { listEmployees } from '../api/employees';
import { listDepartments } from '../api/departments';
import { listLeaveRequests } from '../api/leaveRequests';
import { canWrite } from '../auth/roles';
import {
  MyScheduleDocument,
  ShiftsWithAssignmentsDocument,
  UnderstaffedShiftIdsDocument,
} from '../graphql/generated';
import { FUTURE_WEEKS, addDays, startOfWeek, toDateTime, weekWindow } from '../graphql/time';

interface RestCounts {
  employees: number | null;
  departments: number | null;
  pendingLeave: number | null;
}

const ACTIVE_STATUSES = new Set(['ASSIGNED', 'APPROVED', 'SWAPPED']);

function greeting(): string {
  const hour = new Date().getHours();
  if (hour < 5) return 'Good evening';
  if (hour < 12) return 'Good morning';
  if (hour < 18) return 'Good afternoon';
  return 'Good evening';
}

export default function DashboardPage() {
  const { user } = useAuth();
  const mayWrite = canWrite(user?.roleName);
  const isEmployee = user?.roleName === 'Employee';
  const employeeId = user?.employeeId;

  // Scheduling numbers come from GraphQL (scheduling-query); the rest stays REST.
  const [week] = useState(() => weekWindow(startOfWeek(new Date())));
  const [ahead] = useState(() => {
    const now = new Date();
    return { from: toDateTime(now), to: toDateTime(addDays(startOfWeek(now), 7 * (FUTURE_WEEKS + 1))) };
  });
  const weekShifts = useQuery(ShiftsWithAssignmentsDocument, { variables: week });
  const understaffed = useQuery(UnderstaffedShiftIdsDocument, { variables: week, skip: !mayWrite });
  const mySchedule = useQuery(MyScheduleDocument, {
    variables: { employeeId: employeeId ?? 0, ...ahead },
    skip: !isEmployee || employeeId === undefined,
  });

  const shifts = weekShifts.data?.shifts;
  const shiftsThisWeek = shifts?.filter((s) => s.status !== 'CANCELLED').length ?? null;
  const assignmentsThisWeek = shifts
    ? shifts.reduce(
        (sum, s) => sum + s.assignments.filter((a) => ACTIVE_STATUSES.has(a.status)).length,
        0
      )
    : null;
  const understaffedCount = understaffed.data?.understaffedShifts.length ?? null;
  const myUpcoming = mySchedule.data?.employeeSchedule.filter((a) => ACTIVE_STATUSES.has(a.status));

  const [counts, setCounts] = useState<RestCounts>({
    employees: null,
    departments: null,
    pendingLeave: null,
  });

  useEffect(() => {
    if (!mayWrite) return;
    let cancelled = false;
    Promise.all([
      listEmployees().then((es) => es.length),
      listDepartments().then((ds) => ds.length),
      listLeaveRequests().then(
        (rs) => rs.filter((r) => (r.requestStatus ?? '').toLowerCase() === 'pending').length
      ),
    ])
      .then(([employees, departments, pendingLeave]) => {
        if (!cancelled) setCounts({ employees, departments, pendingLeave });
      })
      .catch(() => {
        /* leave cards blank */
      });
    return () => {
      cancelled = true;
    };
  }, [mayWrite]);

  const data = {
    ...counts,
    shiftsThisWeek,
    assignments: mayWrite ? assignmentsThisWeek : (myUpcoming?.length ?? null),
  };

  const firstName = user?.firstName ?? '';

  const nextShiftLine = useMemo(() => {
    const next = [...(myUpcoming ?? [])].sort((x, y) =>
      x.shift.startDatetime.localeCompare(y.shift.startDatetime)
    )[0];
    if (!next) return null;
    const when = new Date(next.shift.startDatetime).toLocaleString(undefined, {
      weekday: 'short',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
    return `${next.shift.shiftName} · ${when}`;
  }, [myUpcoming]);

  return (
    <div className="page">
      <div className="mb-8">
        <h1 className="text-3xl font-bold tracking-tight text-slate-900">
          {greeting()}
          {firstName ? `, ${firstName}` : ''}.
        </h1>
        <p className="mt-1 text-slate-500">
          {isEmployee
            ? "Here's a look at your upcoming week."
            : "Here's what's happening with your team this week."}
        </p>
      </div>

      <div className="stat-grid">
        <Link to="/shifts" className="stat-card">
          <span className="stat-label">Shifts this week</span>
          <span className="stat-value">{data.shiftsThisWeek ?? '—'}</span>
          <span className="stat-hint">Scheduled in the current week</span>
        </Link>

        <Link
          to={mayWrite ? '/shift-assignments' : '/my-schedule'}
          className="stat-card"
        >
          <span className="stat-label">
            {mayWrite ? 'Assignments' : 'My assignments'}
          </span>
          <span className="stat-value">{data.assignments ?? '—'}</span>
          <span className="stat-hint">
            {mayWrite ? 'Active assignments this week' : 'Upcoming, assigned to you'}
          </span>
        </Link>

        {mayWrite && (
          <Link to="/shifts" className="stat-card">
            <span className="stat-label">Understaffed</span>
            <span className="stat-value">{understaffedCount ?? '—'}</span>
            <span className="stat-hint">Shifts this week missing staff</span>
          </Link>
        )}

        {mayWrite && (
          <Link to="/leave-requests" className="stat-card">
            <span className="stat-label">Pending leave</span>
            <span className="stat-value">{data.pendingLeave ?? '—'}</span>
            <span className="stat-hint">Awaiting your review</span>
          </Link>
        )}

        {mayWrite && (
          <Link to="/employees" className="stat-card">
            <span className="stat-label">Employees</span>
            <span className="stat-value">{data.employees ?? '—'}</span>
            <span className="stat-hint">People on your team</span>
          </Link>
        )}

        {mayWrite && (
          <Link to="/departments" className="stat-card">
            <span className="stat-label">Departments</span>
            <span className="stat-value">{data.departments ?? '—'}</span>
            <span className="stat-hint">Active and inactive</span>
          </Link>
        )}
      </div>

      {isEmployee && (
        <section className="page-section">
          <h2 className="page-section-title">Your next shift</h2>
          {nextShiftLine ? (
            <Link to="/my-schedule" className="stat-card" style={{ maxWidth: 520 }}>
              <span className="stat-label">Coming up</span>
              <span className="stat-value" style={{ fontSize: '1.4rem' }}>
                {nextShiftLine}
              </span>
              <span className="stat-hint">Tap to see your full schedule</span>
            </Link>
          ) : (
            <div className="empty-state">
              <p>No upcoming shifts on your calendar.</p>
            </div>
          )}
        </section>
      )}

      <section className="page-section">
        <h2 className="page-section-title">Quick actions</h2>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {mayWrite && (
            <QuickAction
              to="/shifts/new"
              title="Create a shift"
              description="Add a new shift to the planner"
            />
          )}
          <QuickAction
            to="/shifts"
            title="Plan shifts"
            description="Open the weekly planner"
          />
          <QuickAction
            to="/my-schedule"
            title="My schedule"
            description="View your upcoming shifts"
          />
          <QuickAction
            to="/leave-requests/new"
            title="Request time off"
            description="Submit a new leave request"
          />
          {mayWrite && (
            <QuickAction
              to="/shift-assignments/new"
              title="Assign a shift"
              description="Put someone on a shift"
            />
          )}
          <QuickAction
            to="/leave-requests"
            title="Leave requests"
            description="Track time off and approvals"
          />
        </div>
      </section>
    </div>
  );
}

interface QuickActionProps {
  to: string;
  title: string;
  description: string;
}

function QuickAction({ to, title, description }: QuickActionProps) {
  return (
    <Link
      to={to}
      className="group flex items-center justify-between gap-3 rounded-[10px] border border-slate-200 bg-white px-4 py-3.5 shadow-card transition hover:-translate-y-[1px] hover:border-brand/40 hover:shadow-card-hover hover:no-underline"
    >
      <div className="min-w-0">
        <div className="font-semibold text-slate-900">{title}</div>
        <div className="text-sm text-slate-500">{description}</div>
      </div>
      <span className="text-slate-400 transition group-hover:translate-x-0.5 group-hover:text-brand">
        →
      </span>
    </Link>
  );
}
