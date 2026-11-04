package dk.ek.shift_happens.scheduling.domain;

/** data of scheduling.swap.requested/approved/rejected.v1 (contracts/events/payloads/swap.schema.json). */
public record SwapSnapshot(
        long shiftSwapId,
        long originalShiftAssignmentId,
        long shiftId,
        long employeeFromId,
        long employeeToId,
        String swapStatus,
        String reason,
        java.time.Instant requestDatetime,
        SwapDecision decision) {
}
