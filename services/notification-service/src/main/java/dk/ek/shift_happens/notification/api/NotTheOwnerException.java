package dk.ek.shift_happens.notification.api;

/** The notification exists but belongs to another employee. Rendered as 403 problem+json. */
public class NotTheOwnerException extends RuntimeException {
    public NotTheOwnerException() {
        super("A notification can only be read by the employee it belongs to");
    }
}
