package dk.ek.shift_happens.scheduling.application;

/**
 * The caller is authenticated but not allowed to see this. The GraphQL layer turns it into a FORBIDDEN
 * error, so an Employee asking for somebody else's schedule never gets an empty answer.
 */
public class ViewerNotAllowedException extends RuntimeException {

    public ViewerNotAllowedException(String message) {
        super(message);
    }
}
