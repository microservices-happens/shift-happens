import { apiRequest, pathWithParams } from './client';
import type { paths } from './generated/identity';
import type { SetPasswordRequest, V1Path } from './types';

// identity-service, contracts/openapi/identity.yaml. Login itself lives in auth/AuthContext.tsx
// (`POST /v1/auth/login`).
const ACCOUNT_PASSWORD = '/auth/accounts/{employeeId}/password' satisfies V1Path<keyof paths>;

/**
 * Sets or resets an employee's password (Administrator, or the employee themself). Passwords never
 * pass through workforce-service; the account is created from `workforce.employee.created.v1`,
 * so it exists only some time after the employee was created.
 */
export function setPassword(employeeId: number, password: string): Promise<void> {
  return apiRequest<void>(pathWithParams(ACCOUNT_PASSWORD, { employeeId }), {
    method: 'PUT',
    body: { password } satisfies SetPasswordRequest,
  });
}
