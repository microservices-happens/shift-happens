package dk.ek.shift_happens.leave.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The envelope as the query role reads it (contracts/events/envelope.schema.json). {@code data} stays a JSON tree, so
 * decimals keep their exact value and each event type reads only the fields it needs.
 */
public record ProjectionEnvelope(
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
