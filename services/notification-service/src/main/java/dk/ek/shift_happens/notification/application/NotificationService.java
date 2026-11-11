
package dk.ek.shift_happens.notification.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dk.ek.shift_happens.notification.infrastructure.persistence.NotificationEntity;
import dk.ek.shift_happens.notification.infrastructure.persistence.NotificationRepository;
import dk.ek.shift_happens.notification.infrastructure.persistence.OutboxEventEntity;
import dk.ek.shift_happens.notification.infrastructure.persistence.OutboxEventRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class NotificationService {

    private static final String EMAIL_EVENT_TYPE = "notification.email.requested.v1";
    private static final String TEST_EMAIL = "test@example.invalid";

    private final NotificationRepository notificationRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public NotificationService(
            NotificationRepository notificationRepository,
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper
    ) {
        this.notificationRepository = notificationRepository;
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<NotificationEntity> getNotifications(Long employeeId, boolean unreadOnly) {

        if (unreadOnly) {
            return notificationRepository
                    .findByEmployeeIdAndReadFalseOrderByCreatedAtDesc(employeeId);
        }

        return notificationRepository
                .findByEmployeeIdOrderByCreatedAtDesc(employeeId);
    }

    @Transactional
    public NotificationEntity updateReadStatus(
            Long notificationId,
            Long employeeId,
            boolean read
    ) {

        NotificationEntity notification = notificationRepository
                .findById(notificationId)
                .orElseThrow(() ->
                        new NoSuchElementException("Notification not found")
                );

        if (!notification.getEmployeeId().equals(employeeId)) {
            throw new NoSuchElementException("Notification not found");
        }

        notification.setRead(read);

        return notificationRepository.save(notification);
    }

    @Transactional
    public NotificationEntity createNotification(
            Long employeeId,
            String title,
            String message,
            String sourceEventType,
            UUID sourceEventId
    ) {

        int inserted = notificationRepository.insertIfAbsent(
                employeeId,
                title,
                message,
                sourceEventType,
                sourceEventId
        );

        if (inserted == 0) {
            System.out.println("Duplicate event ignored: " + sourceEventId);
            return null;
        }

        NotificationEntity notification = notificationRepository
                .findByEmployeeIdOrderByCreatedAtDesc(employeeId)
                .stream()
                .filter(n ->
                        sourceEventId.equals(n.getSourceEventId())
                        && employeeId.equals(n.getEmployeeId())
                )
                .findFirst()
                .orElseThrow(() ->
                        new IllegalStateException("Inserted notification not found")
                );

        // Development-only email request.
        // Replace the test recipient with a real email lookup later.
        try {
            UUID emailEventId = UUID.randomUUID();
            OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC);

            String payload = objectMapper.writeValueAsString(Map.of(
                    "eventId", emailEventId.toString(),
                    "eventType", EMAIL_EVENT_TYPE,
                    "occurredAt", occurredAt.toString(),
                    "producer", "notification-service",
                    "aggregateId", employeeId,
                    "data", Map.of(
                            "employeeId", employeeId,
                            "to", TEST_EMAIL,
                            "subject", title,
                            "body", message == null ? "" : message
                    )
            ));

            OutboxEventEntity emailEvent = new OutboxEventEntity(
                    emailEventId,
                    EMAIL_EVENT_TYPE,
                    employeeId,
                    payload,
                    occurredAt
            );

            outboxEventRepository.save(emailEvent);

        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to create email outbox event", e
            );
        }

        return notification;
    }
}
