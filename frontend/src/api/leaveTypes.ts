import { apiRequest } from './client';
import type { paths } from './generated/leave';
import type { LeaveType, V1Path } from './types';

// leave-service (GET goes to the query role), contracts/openapi/leave.yaml
const LEAVE_TYPES = '/leavetypes' satisfies V1Path<keyof paths>;

export function listLeaveTypes(): Promise<LeaveType[]> {
  return apiRequest<LeaveType[]>(LEAVE_TYPES);
}
