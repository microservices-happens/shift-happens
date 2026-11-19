package dk.ek.shift_happens.leave.domain;

import java.time.Instant;

/**
 * The manager's decision about a request; null on the request while it is PENDING (leave-request.schema.json).
 * {@code leaveApprovalId} is the id of the stored approval row; it is null only for a verdict that has not been
 * stored yet (the repository joins the stored approval in, so every published snapshot carries it).
 */
public record LeaveDecision(
        long approverEmployeeId,
        ApprovalDecision decision,
        String comment,
        Instant decisionDatetime,
        Long leaveApprovalId) {

    /** A verdict that is not stored yet. */
    public LeaveDecision(long approverEmployeeId, ApprovalDecision decision, String comment, Instant decisionDatetime) {
        this(approverEmployeeId, decision, comment, decisionDatetime, null);
    }
}
