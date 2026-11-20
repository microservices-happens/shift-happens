import { apiRequest } from './client';
import type { paths } from './generated/workforce';
import type { V1Path, WorkLocation } from './types';

// workforce-service, contracts/openapi/workforce.yaml
const WORK_LOCATIONS = '/worklocations' satisfies V1Path<keyof paths>;

export function listWorkLocations(): Promise<WorkLocation[]> {
  return apiRequest<WorkLocation[]>(WORK_LOCATIONS);
}
