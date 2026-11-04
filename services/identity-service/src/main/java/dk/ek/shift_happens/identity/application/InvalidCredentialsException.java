package dk.ek.shift_happens.identity.application;

/**
 * Login refused. One message for every reason (unknown email, wrong password, no password set,
 * deactivated), so the response does not reveal which emails have accounts.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
