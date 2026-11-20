import { apiRequest, pathWithParams } from './client';
import type { paths } from './generated/scheduling';
import type { NewShift, Shift, V1Path } from './types';

// scheduling-service (command role), contracts/openapi/scheduling.yaml. REST covers writes only;
// commands answer 202 Accepted with the resulting resource.
const SHIFTS = '/shifts' satisfies V1Path<keyof paths>;
const SHIFT = '/shifts/{id}' satisfies V1Path<keyof paths>;

/**
 * @deprecated scheduling.yaml has no `GET /v1/shifts`: all scheduling reads are GraphQL
 * (`/api/graphql`, contracts/graphql/scheduling.graphql). Behind the gateway this request reaches
 * the command role and fails. Replaced by the GraphQL migration in P5.2 (#57).
 */
export function listShifts(): Promise<Shift[]> {
  return apiRequest<Shift[]>(SHIFTS);
}

/** @deprecated No `GET /v1/shifts/{id}` in scheduling.yaml; see {@link listShifts}. */
export function getShift(id: number): Promise<Shift> {
  return apiRequest<Shift>(pathWithParams(SHIFT, { id }));
}

export function createShift(data: NewShift): Promise<Shift> {
  return apiRequest<Shift>(SHIFTS, { method: 'POST', body: data });
}

export function updateShift(id: number, data: NewShift): Promise<Shift> {
  return apiRequest<Shift>(pathWithParams(SHIFT, { id }), { method: 'PUT', body: data });
}

export function deleteShift(id: number): Promise<void> {
  return apiRequest<void>(pathWithParams(SHIFT, { id }), { method: 'DELETE' });
}
