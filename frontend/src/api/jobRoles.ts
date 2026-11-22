import { apiRequest } from './client';
import type { paths } from './generated/workforce';
import type { JobRole, V1Path } from './types';

// workforce-service reference data, contracts/openapi/workforce.yaml
const JOB_ROLES = '/jobroles' satisfies V1Path<keyof paths>;

export function listJobRoles(): Promise<JobRole[]> {
  return apiRequest<JobRole[]>(JOB_ROLES);
}
