import { apiRequest } from './client';
import type { paths } from './generated/leave';
import type { LeaveApproval, NewLeaveApproval, V1Path } from './types';

// leave-service (command role), contracts/openapi/leave.yaml. A decision is the only way to change a
// request's status: REJECTED sets it to REJECTED with rejectionReason = leaveComment; APPROVED moves
// it to APPROVAL_PENDING and starts the leave-approval saga, whose outcome (APPROVED, or REJECTED with
// a rejectionReason) arrives asynchronously. Roles: Administrator, Manager.
const LEAVE_APPROVALS = '/leaveapprovals' satisfies V1Path<keyof paths>;

export function createLeaveApproval(
  data: NewLeaveApproval,
  idempotencyKey: string
): Promise<LeaveApproval> {
  return apiRequest<LeaveApproval>(LEAVE_APPROVALS, { method: 'POST', body: data, idempotencyKey });
}
