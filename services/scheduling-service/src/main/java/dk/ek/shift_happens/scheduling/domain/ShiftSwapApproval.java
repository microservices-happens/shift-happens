package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;

/** A manager's decision about a swap (shift_swap_approval). One per swap. */
public record ShiftSwapApproval(
        Long shiftSwapApprovalId,
        long shiftSwapId,
        long approverEmployeeId,
        ApprovalDecision decision,
        String comment,
        Instant decisionDatetime) {
}
