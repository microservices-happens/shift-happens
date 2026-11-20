import { apiRequest, pathWithParams } from './client';
import type { paths } from './generated/workforce';
import type { Employee, NewEmployee, V1Path } from './types';

// workforce-service, contracts/openapi/workforce.yaml
const EMPLOYEES = '/employees' satisfies V1Path<keyof paths>;
const EMPLOYEE = '/employees/{id}' satisfies V1Path<keyof paths>;

export function listEmployees(): Promise<Employee[]> {
  return apiRequest<Employee[]>(EMPLOYEES);
}

export function getEmployee(id: number): Promise<Employee> {
  return apiRequest<Employee>(pathWithParams(EMPLOYEE, { id }));
}

export function createEmployee(data: NewEmployee, idempotencyKey: string): Promise<Employee> {
  return apiRequest<Employee>(EMPLOYEES, { method: 'POST', body: data, idempotencyKey });
}

export function updateEmployee(id: number, data: Partial<NewEmployee>): Promise<Employee> {
  return apiRequest<Employee>(pathWithParams(EMPLOYEE, { id }), { method: 'PATCH', body: data });
}

export function deleteEmployee(id: number): Promise<void> {
  return apiRequest<void>(pathWithParams(EMPLOYEE, { id }), { method: 'DELETE' });
}
