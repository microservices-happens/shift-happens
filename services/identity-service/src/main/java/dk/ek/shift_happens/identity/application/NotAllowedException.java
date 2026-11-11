package dk.ek.shift_happens.identity.application;

/** The caller is authenticated, but its role does not allow this action (403). */
public class NotAllowedException extends RuntimeException {

    public NotAllowedException(String message) {
        super(message);
    }
}
