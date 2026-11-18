package dk.ek.shift_happens.leave.application;

/** Thrown when a leave type id does not exist (path id). Maps to 404. */
public class LeaveTypeNotFoundException extends RuntimeException {

    public LeaveTypeNotFoundException(long leaveTypeId) {
        super("no leave type with id " + leaveTypeId);
    }
}
