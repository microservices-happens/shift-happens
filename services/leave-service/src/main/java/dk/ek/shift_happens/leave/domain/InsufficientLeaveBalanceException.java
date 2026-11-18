package dk.ek.shift_happens.leave.domain;

/** The entry would make the balance of an employee and leave type negative. Maps to 409. */
public class InsufficientLeaveBalanceException extends RuntimeException {

    public InsufficientLeaveBalanceException(String message) {
        super(message);
    }
}
