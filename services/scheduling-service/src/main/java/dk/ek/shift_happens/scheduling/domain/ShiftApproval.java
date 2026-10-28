package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;

/** A manager's decision about an assignment (shift_approval). One per assignment. */
public record ShiftApproval(
        Long shiftApprovalId,
        long shiftAssignmentId,
        long approverEmployeeId,
        ApprovalDecision decision,
        String approvalComment,
        Instant decisionDatetime) {
}
