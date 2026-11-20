package dk.ek.shift_happens.audit.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One appended event (schema AuditEntry in contracts/openapi/audit.yaml). The envelope is kept exactly as
 * it arrived; the columns beside it exist for filtering.
 */
public record AuditEntry(long auditId, UUID eventId, String eventType, OffsetDateTime occurredAt, String producer,
                         Long aggregateId, Long aggregateVersion, String correlationId, OffsetDateTime receivedAt,
                         JsonNode envelope) {
}
