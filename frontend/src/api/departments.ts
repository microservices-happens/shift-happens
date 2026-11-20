import { apiRequest, pathWithParams } from './client';
import type { paths } from './generated/workforce';
import type { Department, NewDepartment, V1Path } from './types';

// workforce-service, contracts/openapi/workforce.yaml
const DEPARTMENTS = '/departments' satisfies V1Path<keyof paths>;
const DEPARTMENT = '/departments/{id}' satisfies V1Path<keyof paths>;

export function listDepartments(): Promise<Department[]> {
  return apiRequest<Department[]>(DEPARTMENTS);
}

export function getDepartment(id: number): Promise<Department> {
  return apiRequest<Department>(pathWithParams(DEPARTMENT, { id }));
}

export function createDepartment(data: NewDepartment, idempotencyKey: string): Promise<Department> {
  return apiRequest<Department>(DEPARTMENTS, { method: 'POST', body: data, idempotencyKey });
}

export function updateDepartment(id: number, data: Partial<NewDepartment>): Promise<Department> {
  return apiRequest<Department>(pathWithParams(DEPARTMENT, { id }), {
    method: 'PATCH',
    body: data,
  });
}

export function deleteDepartment(id: number): Promise<void> {
  return apiRequest<void>(pathWithParams(DEPARTMENT, { id }), { method: 'DELETE' });
}
