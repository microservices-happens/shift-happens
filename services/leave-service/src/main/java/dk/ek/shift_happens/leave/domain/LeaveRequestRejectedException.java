package dk.ek.shift_happens.leave.domain;

/** A business rule refused the change (wrong status, overlap, inactive employee). Maps to 409. */
public class LeaveRequestRejectedException extends RuntimeException {

    public LeaveRequestRejectedException(String message) {
        super(message);
    }
}
