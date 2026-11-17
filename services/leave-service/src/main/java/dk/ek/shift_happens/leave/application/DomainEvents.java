package dk.ek.shift_happens.leave.application;

import dk.ek.shift_happens.leave.infrastructure.messaging.EventEnvelope;
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
