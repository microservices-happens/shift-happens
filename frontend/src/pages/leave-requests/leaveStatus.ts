import type { LeaveRequestStatus } from '../../api/types';

// requestStatus of contracts/openapi/leave.yaml and the state machine in services/leave-service/README.md:
// PENDING -> APPROVAL_PENDING (approve) | REJECTED (reject) | CANCELLED (DELETE);
// APPROVAL_PENDING -> APPROVED | REJECTED (saga outcome, asynchronous); APPROVED -> CANCELLED.

export const LEAVE_STATUS_LABELS: Record<LeaveRequestStatus, string> = {
  PENDING: 'Pending',
  APPROVAL_PENDING: 'Approval pending',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
};

export const LEAVE_STATUS_PILLS: Record<LeaveRequestStatus, string> = {
  PENDING: 'pill pill-brand',
  APPROVAL_PENDING: 'pill pill-warning',
  APPROVED: 'pill pill-success',
  REJECTED: 'pill pill-danger',
  CANCELLED: 'pill pill-neutral',
};

/** The approval saga is running: the outcome arrives asynchronously, so the view re-reads. */
export function isSagaInFlight(status: LeaveRequestStatus | null | undefined): boolean {
  return status === 'APPROVAL_PENDING';
}

/** No decision can change the request any more without a new user action. */
export function isSettled(status: LeaveRequestStatus | null | undefined): boolean {
  return status === 'APPROVED' || status === 'REJECTED' || status === 'CANCELLED';
}

/** PATCH (Administrator, Manager) is only accepted while PENDING (`409` otherwise). */
export function isEditable(status: LeaveRequestStatus | null | undefined): boolean {
  return status === 'PENDING';
}

/** DELETE cancels a PENDING or APPROVED request; APPROVAL_PENDING and REJECTED answer `409`. */
export function isCancellable(status: LeaveRequestStatus | null | undefined): boolean {
  return status === 'PENDING' || status === 'APPROVED';
}
