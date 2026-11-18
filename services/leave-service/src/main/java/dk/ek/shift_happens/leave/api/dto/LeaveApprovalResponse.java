package dk.ek.shift_happens.leave.api.dto;

import dk.ek.shift_happens.leave.domain.LeaveApproval;

import java.time.Instant;

/** Response body of the leave approval endpoints (contracts/openapi/leave.yaml, schema LeaveApproval). */
public record LeaveApprovalResponse(
        Long leaveApprovalId,
        long leaveRequestId,
        long approverEmployeeId,
        String decision,
        String leaveComment,
        Instant decisionDatetime) {

    public static LeaveApprovalResponse from(LeaveApproval approval) {
        return new LeaveApprovalResponse(approval.leaveApprovalId(), approval.leaveRequestId(),
                approval.approverEmployeeId(), approval.decision().name(), approval.leaveComment(),
                approval.decisionDatetime());
    }
}
