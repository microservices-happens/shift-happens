
package dk.ek.shift_happens.notification.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "notification")
public class NotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "message")
    private String message;

    @Column(name = "source_event_type", nullable = false)
    private String sourceEventType;

    @Column(name = "source_event_id", nullable = false)
    private UUID sourceEventId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "is_read", nullable = false)
    private boolean read = false;

    protected NotificationEntity() {
    }

    public NotificationEntity(
            Long employeeId,
            String title,
            String message,
            String sourceEventType,
            UUID sourceEventId
    ) {
        this.employeeId = employeeId;
        this.title = title;
        this.message = message;
        this.sourceEventType = sourceEventType;
        this.sourceEventId = sourceEventId;
        this.createdAt = OffsetDateTime.now();
    }


public Long getNotificationId() {
    return notificationId;
}

public Long getEmployeeId() {
    return employeeId;
}

public String getTitle() {
    return title;
}

public String getMessage() {
    return message;
}

public String getSourceEventType() {
    return sourceEventType;
}

public UUID getSourceEventId() {
    return sourceEventId;
}

public OffsetDateTime getCreatedAt() {
    return createdAt;
}

public boolean isRead() {
    return read;
}

public void setRead(boolean read) {
    this.read = read;
}

}
