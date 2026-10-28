package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.ShiftApproval;

import java.time.Instant;

/** Response body of the approval endpoints (contracts/openapi/scheduling.yaml). */
public record ShiftApprovalResponse(
        Long shiftApprovalId,
        long shiftAssignmentId,
        long approverEmployeeId,
        String decision,
        String approvalComment,
        Instant decisionDatetime) {

    public static ShiftApprovalResponse from(ShiftApproval approval) {
        return new ShiftApprovalResponse(approval.shiftApprovalId(), approval.shiftAssignmentId(),
                approval.approverEmployeeId(), approval.decision().name(), approval.approvalComment(),
                approval.decisionDatetime());
    }
}
