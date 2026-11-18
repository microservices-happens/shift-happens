package dk.ek.shift_happens.leave.application;

/** Thrown when a leave request id does not exist. Maps to 404. */
public class LeaveRequestNotFoundException extends RuntimeException {

    public LeaveRequestNotFoundException(long leaveRequestId) {
        super("no leave request with id " + leaveRequestId);
    }
}
