import { Link } from 'react-router-dom';
import { useQuery } from '@apollo/client/react';
import { graphqlErrorMessage } from '../../graphql/client';
import {
  UnderstaffedShiftsDocument,
  type UnderstaffedShiftsQuery,
} from '../../graphql/generated';
import { useAssignEmployee } from '../../graphql/useAssignEmployee';

type UnderstaffedShift = UnderstaffedShiftsQuery['understaffedShifts'][number];
type Suggestion = UnderstaffedShift['replacementSuggestions'][number];

const ACTIVE_STATUSES = new Set(['ASSIGNED', 'APPROVED', 'SWAPPED']);

/** The contract lists suggestions newest first; the first one per job role is the current one. */
function latestPerJobRole(suggestions: Suggestion[]): Map<number, Suggestion> {
  const latest = new Map<number, Suggestion>();
  for (const s of suggestions) if (!latest.has(s.jobRoleId)) latest.set(s.jobRoleId, s);
  return latest;
}

function formatWhen(start: string, end: string): string {
  const s = new Date(start);
  const e = new Date(end);
  const time = (d: Date) => d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return `${s.toLocaleDateString(undefined, { weekday: 'short', day: 'numeric', month: 'short' })} · ${time(s)} – ${time(e)}`;
}

/**
 * Manager view of `understaffedShifts(from, to)` with the AI replacement suggestions
 * (`Shift.replacementSuggestions`, from ai.replacement.suggested.v1). Assigning a candidate is a REST
 * POST to scheduling-command, after which the GraphQL views on screen are refetched.
 */
export function UnderstaffedShiftsPanel({ from, to }: { from: string; to: string }) {
  const { data, loading, error } = useQuery(UnderstaffedShiftsDocument, { variables: { from, to } });
  const { assign, isPending, error: assignError } = useAssignEmployee();

  const shifts = data?.understaffedShifts ?? [];

  return (
    <section className="page-section">
      <h2 className="page-section-title">Understaffed shifts this week</h2>
      {error && (
        <div className="alert alert-error">
          {graphqlErrorMessage(error, 'Failed to load understaffed shifts')}
        </div>
      )}
      {assignError && <div className="alert alert-error">{assignError}</div>}
      {loading && !data && <div className="page-muted">Loading…</div>}
      {data && shifts.length === 0 && (
        <div className="empty-state">
          <p>Every shift this week is fully staffed.</p>
        </div>
      )}

      {shifts.map((shift) => {
        const suggestions = latestPerJobRole(shift.replacementSuggestions);
        const assigned = new Set(
          shift.assignments
            .filter((a) => ACTIVE_STATUSES.has(a.status))
            .map((a) => a.employee.employeeId)
        );
        return (
          <div key={shift.shiftId} className="card card--padded" style={{ marginBottom: '0.75rem' }}>
            <div className="name-cell-primary">
              <Link to={`/shifts/${shift.shiftId}`}>{shift.shiftName}</Link>
            </div>
            <div className="name-cell-secondary">
              {formatWhen(shift.startDatetime, shift.endDatetime)}
            </div>

            {shift.requiredJobRoles
              .filter((role) => role.assignedCount < role.requiredEmployeeCount)
              .map((role) => {
                const suggestion = suggestions.get(role.jobRoleId);
                return (
                  <div key={role.jobRoleId} style={{ marginTop: '0.75rem' }}>
                    <div>
                      <span className="pill pill-danger">
                        Job role #{role.jobRoleId}: {role.assignedCount}/{role.requiredEmployeeCount}
                      </span>
                    </div>
                    {!suggestion ? (
                      <p className="page-muted">No replacement suggestion yet.</p>
                    ) : (
                      <div style={{ marginTop: '0.5rem' }}>
                        <p className="page-muted">
                          Suggested by {suggestion.source === 'LLM' ? 'AI' : 'rule fallback'}
                          {suggestion.model ? ` (${suggestion.model})` : ''} ·{' '}
                          {new Date(suggestion.suggestedAt).toLocaleString()}
                        </p>
                        <p>{suggestion.reason}</p>
                        <ol>
                          {[...suggestion.candidates]
                            .sort((a, b) => a.rank - b.rank)
                            .map(({ rank, employee }) => {
                              const already = assigned.has(employee.employeeId);
                              const pending = isPending(shift.shiftId, employee.employeeId);
                              return (
                                <li key={employee.employeeId} style={{ marginTop: '0.25rem' }}>
                                  {rank}. {employee.firstName} {employee.lastName}{' '}
                                  {already ? (
                                    <span className="pill pill-neutral">Assigned</span>
                                  ) : (
                                    <button
                                      type="button"
                                      className="btn btn-sm btn-primary"
                                      disabled={pending}
                                      onClick={() => void assign(shift.shiftId, employee.employeeId)}
                                    >
                                      {pending ? 'Assigning…' : 'Assign'}
                                    </button>
                                  )}
                                </li>
                              );
                            })}
                        </ol>
                      </div>
                    )}
                  </div>
                );
              })}
          </div>
        );
      })}
    </section>
  );
}
