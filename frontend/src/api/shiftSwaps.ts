import { apiRequest } from './client';
import type { paths } from './generated/scheduling';
import type { NewShiftSwap, NewShiftSwapApproval, ShiftSwap, ShiftSwapApproval, V1Path } from './types';

// scheduling-service (command role), contracts/openapi/scheduling.yaml. Reads are GraphQL
// (swapRequests on scheduling-query); these are the two writes.
const SHIFT_SWAPS = '/shiftswaps' satisfies V1Path<keyof paths>;
const SHIFT_SWAP_APPROVALS = '/shiftswapapprovals' satisfies V1Path<keyof paths>;

export function createShiftSwap(data: NewShiftSwap, idempotencyKey: string): Promise<ShiftSwap> {
  return apiRequest<ShiftSwap>(SHIFT_SWAPS, { method: 'POST', body: data, idempotencyKey });
}

export function createShiftSwapApproval(
  data: NewShiftSwapApproval,
  idempotencyKey: string
): Promise<ShiftSwapApproval> {
  return apiRequest<ShiftSwapApproval>(SHIFT_SWAP_APPROVALS, {
    method: 'POST',
    body: data,
    idempotencyKey,
  });
}
