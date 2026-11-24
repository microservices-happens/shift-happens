import { apiRequest, pathWithParams } from './client';
import type { paths } from './generated/workforce';
import type { EmployeeJobRole, NewEmployeeJobRole, V1Path } from './types';

// workforce-service, contracts/openapi/workforce.yaml. A role row publishes workforce.employee.updated.v1,
// so Scheduling's replica learns the new jobRoleIds without a synchronous call.
const EMPLOYEE_JOB_ROLES = '/employeejobroles' satisfies V1Path<keyof paths>;
const EMPLOYEE_JOB_ROLE = '/employeejobroles/{id}' satisfies V1Path<keyof paths>;

export function listEmployeeJobRoles(): Promise<EmployeeJobRole[]> {
  return apiRequest<EmployeeJobRole[]>(EMPLOYEE_JOB_ROLES);
}

export function createEmployeeJobRole(
  data: NewEmployeeJobRole,
  idempotencyKey: string
): Promise<EmployeeJobRole> {
  return apiRequest<EmployeeJobRole>(EMPLOYEE_JOB_ROLES, {
    method: 'POST',
    body: data,
    idempotencyKey,
  });
}

export function deleteEmployeeJobRole(id: number): Promise<void> {
  return apiRequest<void>(pathWithParams(EMPLOYEE_JOB_ROLE, { id }), { method: 'DELETE' });
}
