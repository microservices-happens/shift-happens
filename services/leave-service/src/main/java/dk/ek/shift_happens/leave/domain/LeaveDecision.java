package dk.ek.shift_happens.leave.domain;

import java.time.Instant;

/** The manager's decision about a request; null on the request while it is PENDING (leave-request.schema.json). */
public record LeaveDecision(
        long approverEmployeeId,
        ApprovalDecision decision,
        String comment,
        Instant decisionDatetime) {
}
