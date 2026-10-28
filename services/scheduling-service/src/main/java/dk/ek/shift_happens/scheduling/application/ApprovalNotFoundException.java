package dk.ek.shift_happens.scheduling.application;

/** Thrown when an approval id does not exist. Maps to 404. */
public class ApprovalNotFoundException extends RuntimeException {

    public ApprovalNotFoundException(long shiftApprovalId) {
        super("no approval with id " + shiftApprovalId);
    }
}
