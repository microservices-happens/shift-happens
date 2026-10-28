package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes an event to the transactional outbox in the caller'"'"'s transaction, with the envelope of
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

    private final EventOutbox outbox;
    private final String producer;

    DomainEvents(EventOutbox outbox, @Value("${app.events.producer}") String producer) {
        this.outbox = outbox;
        this.producer = producer;
    }

    public void append(String eventType, long aggregateId, long aggregateVersion, String correlationId, Object data) {
        outbox.append(new EventEnvelope(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                producer,
                aggregateId,
                aggregateVersion,
                correlationIdOf(correlationId),
                // A REST command has no causing event; the saga replies in P3.1 will set it.
                null,
                data));
    }

    private static String correlationIdOf(String correlationId) {
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }
}
