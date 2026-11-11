
package dk.ek.shift_happens.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

public record EventEnvelope(
        UUID eventId,
        String eventType,
        OffsetDateTime occurredAt,
        String producer,
        Long aggregateId,
        Integer aggregateVersion,
        String correlationId,
        UUID causationId,
        JsonNode data
) {
}
