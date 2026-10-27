package dk.ek.shift_happens.scheduling.infrastructure.messaging;

import java.time.Instant;
import java.util.UUID;

/**
 * The body of every message, matching contracts/events/envelope.schema.json.
 *
 * <p>The routing key is {@code eventType}. {@code data} is a full snapshot of the entity
 * (snapshot pattern), so a consumer never has to call back.
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        String producer,
        long aggregateId,
        long aggregateVersion,
        String correlationId,
        UUID causationId,
        Object data) {
}
