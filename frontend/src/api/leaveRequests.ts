import { apiRequest, pathWithParams } from './client';
import type { paths } from './generated/leave';
import type { LeaveRequest, NewLeaveRequest, V1Path } from './types';

// leave-service, contracts/openapi/leave.yaml. The gateway sends GET to the query role and the
// other methods to the command role; commands answer 202 Accepted and reads catch up from events.
const LEAVE_REQUESTS = '/leaverequests' satisfies V1Path<keyof paths>;
const LEAVE_REQUEST = '/leaverequests/{id}' satisfies V1Path<keyof paths>;

export function listLeaveRequests(): Promise<LeaveRequest[]> {
  return apiRequest<LeaveRequest[]>(LEAVE_REQUESTS);
}

export function getLeaveRequest(id: number): Promise<LeaveRequest> {
  return apiRequest<LeaveRequest>(pathWithParams(LEAVE_REQUEST, { id }));
}

export function createLeaveRequest(data: NewLeaveRequest): Promise<LeaveRequest> {
  return apiRequest<LeaveRequest>(LEAVE_REQUESTS, { method: 'POST', body: data });
}

export function updateLeaveRequest(
  id: number,
  data: Partial<NewLeaveRequest>
): Promise<LeaveRequest> {
  return apiRequest<LeaveRequest>(pathWithParams(LEAVE_REQUEST, { id }), {
    method: 'PATCH',
    body: data,
  });
}

export function deleteLeaveRequest(id: number): Promise<void> {
  return apiRequest<void>(pathWithParams(LEAVE_REQUEST, { id }), { method: 'DELETE' });
}
