import type { LeaveRequestStatus } from '../../api/types';
import { LEAVE_STATUS_LABELS, LEAVE_STATUS_PILLS, isSagaInFlight } from './leaveStatus';

export function LeaveStatusPill({ status }: { status: LeaveRequestStatus | null | undefined }) {
  if (!status) return <span className="pill pill-neutral">Unknown</span>;
  const inFlight = isSagaInFlight(status);
  return (
    <span className={LEAVE_STATUS_PILLS[status]} title={inFlight ? 'Waiting for Scheduling to release the shifts' : undefined}>
      <span className={inFlight ? 'pill-dot pill-dot--pulse' : 'pill-dot'} />
      {LEAVE_STATUS_LABELS[status]}
    </span>
  );
}
