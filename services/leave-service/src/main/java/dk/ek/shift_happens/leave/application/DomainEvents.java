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

    public static final String LEAVE_REQUEST_SUBMITTED = "leave.request.submitted.v1";
    public static final String LEAVE_REQUEST_APPROVAL_STARTED = "leave.request.approval-started.v1";
    public static final String LEAVE_REQUEST_APPROVED = "leave.request.approved.v1";
    public static final String LEAVE_REQUEST_REJECTED = "leave.request.rejected.v1";
    public static final String LEAVE_REQUEST_CANCELLED = "leave.request.cancelled.v1";
    public static final String LEAVE_LEDGER_ENTRY_RECORDED = "leave.ledger.entry-recorded.v1";

    private final EventOutbox outbox;
    private final String producer;

    DomainEvents(EventOutbox outbox, @Value("${app.events.producer}") String producer) {
        this.outbox = outbox;
        this.producer = producer;
    }

    /** A command that no event caused: no causationId. */
    public EventEnvelope append(String eventType, long aggregateId, long aggregateVersion, String correlationId,
                                Object data) {
        return append(eventType, aggregateId, aggregateVersion, correlationId, null, data);
    }

    /**
     * A reaction to another event: causationId is the eventId that caused this one. Returns the envelope that was
     * written, so a caller can learn the eventId (the saga correlates replies on it).
     */
    public EventEnvelope append(String eventType, long aggregateId, long aggregateVersion, String correlationId,
                                UUID causationId, Object data) {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                producer,
                aggregateId,
                aggregateVersion,
                correlationIdOf(correlationId),
                causationId,
                data);
        outbox.append(envelope);
        return envelope;
    }

    private static String correlationIdOf(String correlationId) {
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }
}
