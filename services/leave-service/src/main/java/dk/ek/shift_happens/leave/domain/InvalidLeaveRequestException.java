package dk.ek.shift_happens.leave.domain;

/** The input is malformed or refers to something that does not exist (date order, unknown type). Maps to 400. */
public class InvalidLeaveRequestException extends RuntimeException {

    public InvalidLeaveRequestException(String message) {
        super(message);
    }
}
