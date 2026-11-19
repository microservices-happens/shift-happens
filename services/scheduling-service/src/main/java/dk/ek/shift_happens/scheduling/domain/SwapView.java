package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;

/** A swap request as the read model keeps it, built from scheduling.swap.requested/approved/rejected.v1. */
public record SwapView(
        long shiftSwapId,
        long originalShiftAssignmentId,
        long shiftId,
        long employeeFromId,
        long employeeToId,
        SwapStatus status,
        String reason,
        Instant requestDatetime,
        SwapDecision decision,
        long aggregateVersion) {

    public boolean involves(long employeeId) {
        return employeeFromId == employeeId || employeeToId == employeeId;
    }
}
