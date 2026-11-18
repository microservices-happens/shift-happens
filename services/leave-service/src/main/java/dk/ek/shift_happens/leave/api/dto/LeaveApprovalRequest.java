package dk.ek.shift_happens.leave.api.dto;

import dk.ek.shift_happens.leave.domain.ApprovalDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Request body of POST and PUT /v1/leaveapprovals (contracts/openapi/leave.yaml, schema LeaveApproval). */
public record LeaveApprovalRequest(
        @NotNull @Positive Long leaveRequestId,
        @NotNull @Positive Long approverEmployeeId,
        @NotNull ApprovalDecision decision,
        String leaveComment) {
}
