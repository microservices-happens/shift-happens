import { apiRequest, pathWithParams } from './client';
import type { paths } from './generated/scheduling';
import type { NewShiftAssignment, ShiftAssignment, V1Path } from './types';

// scheduling-service (command role), contracts/openapi/scheduling.yaml. REST covers writes only;
// commands answer 202 Accepted with the resulting resource. Reads are GraphQL: see src/graphql.
const SHIFT_ASSIGNMENTS = '/shiftassignments' satisfies V1Path<keyof paths>;
const SHIFT_ASSIGNMENT = '/shiftassignments/{id}' satisfies V1Path<keyof paths>;

export function createShiftAssignment(data: NewShiftAssignment): Promise<ShiftAssignment> {
  return apiRequest<ShiftAssignment>(SHIFT_ASSIGNMENTS, { method: 'POST', body: data });
}

export function updateShiftAssignment(
  id: number,
  data: NewShiftAssignment
): Promise<ShiftAssignment> {
  return apiRequest<ShiftAssignment>(pathWithParams(SHIFT_ASSIGNMENT, { id }), {
    method: 'PUT',
    body: data,
  });
}

export function deleteShiftAssignment(id: number): Promise<void> {
  return apiRequest<void>(pathWithParams(SHIFT_ASSIGNMENT, { id }), { method: 'DELETE' });
}
