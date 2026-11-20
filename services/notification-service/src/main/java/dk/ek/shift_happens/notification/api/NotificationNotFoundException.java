package dk.ek.shift_happens.notification.api;

/** The caller asked for a notification that does not exist. Rendered as 404 problem+json. */
public class NotificationNotFoundException extends RuntimeException {
    public NotificationNotFoundException(long notificationId) {
        super("Notification " + notificationId + " does not exist");
    }
}
