package dk.ek.shift_happens.leave.domain;

/**
 * A kind of leave (vacation, sick leave, ...). Plain CRUD: the event catalog defines no leave-type
 * events, so changing one publishes nothing.
 */
public record LeaveType(
        Long leaveTypeId,
        String leaveTypeName,
        String leaveTypeDescription,
        boolean requiresApproval,
        boolean paidLeave) {

    public LeaveType {
        if (leaveTypeName == null || leaveTypeName.isBlank()) {
            throw new InvalidLeaveRequestException("leaveTypeName must not be blank");
        }
    }

    public LeaveType withId(long id) {
        return new LeaveType(id, leaveTypeName, leaveTypeDescription, requiresApproval, paidLeave);
    }
}
