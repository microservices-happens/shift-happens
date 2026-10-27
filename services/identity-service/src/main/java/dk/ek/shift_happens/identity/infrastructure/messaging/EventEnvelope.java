package dk.ek.shift_happens.identity.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The body of every message, matching contracts/events/envelope.schema.json.
 *
 * <p>{@code data} is kept as a {@link JsonNode} and mapped to the event-specific payload afterwards,
 * so the listener does not need one class per event type.
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
        JsonNode data) {
}
