package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes an event to the transactional outbox in the caller's transaction, with the envelope of
 * contracts/events/envelope.schema.json. One place builds envelopes, so the producer and the
 * correlation-id rule cannot drift between use cases.
 */
@Component
@Profile("command")
public class DomainEvents {

    public static final String SHIFT_CREATED = "scheduling.shift.created.v1";
    public static final String SHIFT_UPDATED = "scheduling.shift.updated.v1";
    public static final String SHIFT_DELETED = "scheduling.shift.deleted.v1";
    public static final String ASSIGNMENT_CREATED = "scheduling.assignment.created.v1";
    public static final String ASSIGNMENT_UPDATED = "scheduling.assignment.updated.v1";
    public static final String ASSIGNMENT_DELETED = "scheduling.assignment.deleted.v1";
    public static final String SWAP_REQUESTED = "scheduling.swap.requested.v1";
    public static final String SWAP_APPROVED = "scheduling.swap.approved.v1";
    public static final String SWAP_REJECTED = "scheduling.swap.rejected.v1";
    public static final String LEAVE_RELEASE_COMPLETED = "scheduling.leave-release.completed.v1";
    public static final String LEAVE_RELEASE_REJECTED = "scheduling.leave-release.rejected.v1";

    private final EventOutbox outbox;
    private final String producer;

    DomainEvents(EventOutbox outbox, @Value("${app.events.producer}") String producer) {
        this.outbox = outbox;
        this.producer = producer;
    }

    /** A command that no event caused: no causationId. */
    public void append(String eventType, long aggregateId, long aggregateVersion, String correlationId,
                       Object data) {
        append(eventType, aggregateId, aggregateVersion, correlationId, null, data);
    }

    /** A reaction to another event: causationId is the eventId that caused this one. */
    public void append(String eventType, long aggregateId, long aggregateVersion, String correlationId,
                       UUID causationId, Object data) {
        outbox.append(new EventEnvelope(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                producer,
                aggregateId,
                aggregateVersion,
                correlationIdOf(correlationId),
                causationId,
                data));
    }

    private static String correlationIdOf(String correlationId) {
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }
}
