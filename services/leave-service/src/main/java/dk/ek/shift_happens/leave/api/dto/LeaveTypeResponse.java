package dk.ek.shift_happens.leave.api.dto;

import dk.ek.shift_happens.leave.domain.LeaveType;

/** Response body of the leave type endpoints (contracts/openapi/leave.yaml, schema LeaveType). */
public record LeaveTypeResponse(
        Long leaveTypeId,
        String leaveTypeName,
        String leaveTypeDescription,
        boolean requiresApproval,
        boolean isPaidLeave) {

    public static LeaveTypeResponse from(LeaveType type) {
        return new LeaveTypeResponse(type.leaveTypeId(), type.leaveTypeName(), type.leaveTypeDescription(),
                type.requiresApproval(), type.paidLeave());
    }
}
