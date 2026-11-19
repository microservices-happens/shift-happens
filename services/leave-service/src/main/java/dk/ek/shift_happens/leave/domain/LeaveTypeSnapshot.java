package dk.ek.shift_happens.leave.domain;

/** data of leave.type.created.v1 / leave.type.updated.v1 (contracts/events/payloads/leave-type.schema.json). */
public record LeaveTypeSnapshot(
        long leaveTypeId,
        String leaveTypeName,
        String leaveTypeDescription,
        boolean requiresApproval,
        boolean isPaidLeave) {
}
