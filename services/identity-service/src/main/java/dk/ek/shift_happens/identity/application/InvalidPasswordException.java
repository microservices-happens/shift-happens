package dk.ek.shift_happens.identity.application;

/** The new password cannot be stored (400). */
public class InvalidPasswordException extends RuntimeException {

    public InvalidPasswordException(String message) {
        super(message);
    }
}
