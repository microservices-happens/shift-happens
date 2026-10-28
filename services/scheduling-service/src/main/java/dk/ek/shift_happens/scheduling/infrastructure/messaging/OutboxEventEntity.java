package dk.ek.shift_happens.scheduling.infrastructure.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One row of the transactional outbox: an event waiting to be published to RabbitMQ. */
@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 120)
    private String eventType;

    @Column(name = "aggregate_id", nullable = false)
    private long aggregateId;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    protected OutboxEventEntity() {
        // for JPA
    }

    OutboxEventEntity(UUID eventId, String eventType, long aggregateId, String payload, Instant occurredAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.aggregateId = aggregateId;
        this.payload = payload;
        this.occurredAt = occurredAt;
    }

    UUID getEventId() {
        return eventId;
    }

    String getEventType() {
        return eventType;
    }

    long getAggregateId() {
        return aggregateId;
    }

    String getPayload() {
        return payload;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    int getAttempts() {
        return attempts;
    }

    void recordFailure() {
        this.attempts++;
    }
}
