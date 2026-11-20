package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One envelope the query role has seen, kept so the read model can be rebuilt (P2.10).
 *
 * <p>RabbitMQ does not retain history, so the projection stores every applied event itself and the
 * rebuild replays that log into empty collections. {@code payload} is the envelope's {@code data} as
 * it arrived, which is what makes a replay byte-for-byte the same input as the live path.
 */
public record ProjectionEvent(
        UUID eventId,
        String eventType,
        String producer,
        long aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        String correlationId,
        UUID causationId,
        Object payload) {
}
