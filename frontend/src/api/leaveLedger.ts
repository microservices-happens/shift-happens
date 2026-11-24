import { apiRequest } from './client';
import type { paths } from './generated/leave';
import type { LeaveLedgerEntry, NewLeaveLedgerEntry, V1Path } from './types';

// leave-service (command role), contracts/openapi/leave.yaml. The ledger is append-only: an accrual is a
// positive entry, a correction is a new entry with the opposite amount.
const LEDGER = '/leaveledgers' satisfies V1Path<keyof paths>;

export function createLedgerEntry(
  data: NewLeaveLedgerEntry,
  idempotencyKey: string
): Promise<LeaveLedgerEntry> {
  return apiRequest<LeaveLedgerEntry>(LEDGER, { method: 'POST', body: data, idempotencyKey });
}
