
package dk.ek.shift_happens.notification.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected OutboxEventEntity() {
    }

    public OutboxEventEntity(
            String eventType,
            Long aggregateId,
            String payload
    ) {
        this(
                UUID.randomUUID(),
                eventType,
                aggregateId,
                payload,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    public OutboxEventEntity(
            UUID eventId,
            String eventType,
            Long aggregateId,
            String payload,
            OffsetDateTime occurredAt
    ) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.occurredAt = occurredAt;
        this.publishedAt = null;
        this.attempts = 0;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public String getPayload() {
        return payload;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public int getAttempts() {
        return attempts;
    }

    public void markPublished() {
        this.publishedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public void incrementAttempts() {
        this.attempts++;
    }
}
