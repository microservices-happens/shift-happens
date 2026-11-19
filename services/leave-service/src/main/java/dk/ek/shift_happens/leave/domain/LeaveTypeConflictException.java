package dk.ek.shift_happens.leave.domain;

/** A leave type name is taken, or a type that requests still use cannot be deleted. Maps to 409. */
public class LeaveTypeConflictException extends RuntimeException {

    public LeaveTypeConflictException(String message) {
        super(message);
    }
}
