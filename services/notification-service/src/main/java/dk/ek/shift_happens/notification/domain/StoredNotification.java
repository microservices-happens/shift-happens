package dk.ek.shift_happens.notification.domain;

import java.time.Instant;
import java.util.UUID;

/** A stored in-app notification (schema Notification in contracts/openapi/notification.yaml). */
public record StoredNotification(long notificationId, long employeeId, String title, String message,
                                 String sourceEventType, UUID sourceEventId, Instant createdAt, boolean read) {
}
