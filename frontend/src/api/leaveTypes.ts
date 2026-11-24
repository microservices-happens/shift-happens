import { apiRequest } from './client';
import type { paths } from './generated/leave';
import type { LeaveType, NewLeaveType, V1Path } from './types';

// leave-service (GET goes to the query role), contracts/openapi/leave.yaml
const LEAVE_TYPES = '/leavetypes' satisfies V1Path<keyof paths>;

export function listLeaveTypes(): Promise<LeaveType[]> {
  return apiRequest<LeaveType[]>(LEAVE_TYPES);
}

export function createLeaveType(data: NewLeaveType, idempotencyKey: string): Promise<LeaveType> {
  return apiRequest<LeaveType>(LEAVE_TYPES, { method: 'POST', body: data, idempotencyKey });
}
