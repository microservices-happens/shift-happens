package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;

/** The manager's decision about a swap; null while the swap is PENDING (swap.schema.json). */
public record SwapDecision(
        long approverEmployeeId,
        ApprovalDecision decision,
        String comment,
        Instant decisionDatetime) {
}
