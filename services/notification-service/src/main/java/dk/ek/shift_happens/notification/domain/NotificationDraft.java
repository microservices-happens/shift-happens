package dk.ek.shift_happens.notification.domain;


/** One notification to store, before it has an id. Produced by {@link NotificationPolicy}. */
public record NotificationDraft(long employeeId, String title, String message) {
}
