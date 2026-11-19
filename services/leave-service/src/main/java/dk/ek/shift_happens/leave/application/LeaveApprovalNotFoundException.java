package dk.ek.shift_happens.leave.application;

/** Thrown when a leave approval id does not exist. Maps to 404. */
public class LeaveApprovalNotFoundException extends RuntimeException {

    public LeaveApprovalNotFoundException(long leaveApprovalId) {
        super("no leave approval with id " + leaveApprovalId);
    }
}
