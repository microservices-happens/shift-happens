package dk.ek.shift_happens.leave.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body of POST and PUT /v1/leavetypes (contracts/openapi/leave.yaml, schema LeaveType).
 * Omitted flags default to true: a new type needs approval and is paid unless said otherwise.
 */
public record LeaveTypeRequest(
        @NotBlank @Size(max = 100) String leaveTypeName,
        String leaveTypeDescription,
        Boolean requiresApproval,
        Boolean isPaidLeave) {

    public boolean requiresApprovalOrDefault() {
        return requiresApproval == null || requiresApproval;
    }

    public boolean paidLeaveOrDefault() {
        return isPaidLeave == null || isPaidLeave;
    }
}
