import { apiRequest, pathWithParams } from './client';
import type { paths } from './generated/scheduling';
import type { NewShift, Shift, V1Path } from './types';

// scheduling-service (command role), contracts/openapi/scheduling.yaml. REST covers writes only;
// commands answer 202 Accepted with the resulting resource. Reads are GraphQL: see src/graphql.
const SHIFTS = '/shifts' satisfies V1Path<keyof paths>;
const SHIFT = '/shifts/{id}' satisfies V1Path<keyof paths>;

export function createShift(data: NewShift, idempotencyKey: string): Promise<Shift> {
  return apiRequest<Shift>(SHIFTS, { method: 'POST', body: data, idempotencyKey });
}

export function updateShift(id: number, data: NewShift): Promise<Shift> {
  return apiRequest<Shift>(pathWithParams(SHIFT, { id }), { method: 'PUT', body: data });
}

export function deleteShift(id: number): Promise<void> {
  return apiRequest<void>(pathWithParams(SHIFT, { id }), { method: 'DELETE' });
}
