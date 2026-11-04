package dk.ek.shift_happens.identity.application;

/** No account for this employee id (it has not arrived from workforce, or never existed). */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(long employeeId) {
        super("No account for employee " + employeeId);
    }
}
