package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.ShiftSwap;

import java.time.Instant;

/** Response body of the swap endpoints (contracts/openapi/scheduling.yaml, schema ShiftSwap). */
public record ShiftSwapResponse(
        Long shiftSwapId,
        long originalShiftAssignmentId,
        long shiftId,
        long employeeFromId,
        long employeeToId,
        String swapStatus,
        String reason,
        Instant requestDatetime,
        SwapDecisionResponse decision) {

    public static ShiftSwapResponse from(ShiftSwap swap) {
        return new ShiftSwapResponse(swap.shiftSwapId(), swap.originalShiftAssignmentId(), swap.shiftId(),
                swap.employeeFromId(), swap.employeeToId(), swap.status().name(), swap.reason(),
                swap.requestDatetime(), SwapDecisionResponse.from(swap.decision()));
    }
}
