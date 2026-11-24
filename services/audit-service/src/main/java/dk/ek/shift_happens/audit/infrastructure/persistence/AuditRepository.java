package dk.ek.shift_happens.audit.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.audit.domain.AuditEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Append and search. There is deliberately no update or delete: an audit trail that can be rewritten is
 * not an audit trail. Idempotent by {@code eventId} (a redelivery is ignored by the unique constraint).
 */
@Component
public class AuditRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    private RowMapper<AuditEntry> mapper() {
        return (rs, row) -> new AuditEntry(
                rs.getLong("audit_id"),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getString("producer"),
                rs.getObject("aggregate_id", Long.class),
                rs.getObject("aggregate_version", Long.class),
                rs.getString("correlation_id"),
                rs.getObject("received_at", OffsetDateTime.class),
                readTree(rs.getString("envelope")));
    }

    /** Stores the envelope unchanged. Returns false when this eventId was already appended. */
    public boolean append(JsonNode envelope) {
        return jdbc.update("""
                INSERT INTO audit_entry (event_id,event_type,occurred_at,producer,aggregate_id,
                                         aggregate_version,correlation_id,envelope)
                VALUES (?,?,?,?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT (event_id) DO NOTHING
                """,
                UUID.fromString(envelope.path("eventId").asText()),
                envelope.path("eventType").asText(),
                OffsetDateTime.parse(envelope.path("occurredAt").asText()),
                envelope.path("producer").asText(),
                envelope.path("aggregateId").isMissingNode() ? null : envelope.path("aggregateId").asLong(),
                envelope.path("aggregateVersion").isMissingNode() ? null : envelope.path("aggregateVersion").asLong(),
                envelope.path("correlationId").asText(null),
                envelope.toString()) > 0;
    }

    /** Any combination of filters; newest first, at most {@code maxRows}. */
    public List<AuditEntry> search(String eventType, Long aggregateId, String correlationId,
                                   OffsetDateTime from, OffsetDateTime to, int maxRows) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_entry WHERE 1=1");
        List<Object> parameters = new ArrayList<>();
        if (eventType != null && !eventType.isBlank()) {
            sql.append(" AND event_type=?");
            parameters.add(eventType);
        }
        if (aggregateId != null) {
            sql.append(" AND aggregate_id=?");
            parameters.add(aggregateId);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            sql.append(" AND correlation_id=?");
            parameters.add(correlationId);
        }
        if (from != null) {
            sql.append(" AND occurred_at>=?");
            parameters.add(from);
        }
        if (to != null) {
            sql.append(" AND occurred_at<=?");
            parameters.add(to);
        }
        sql.append(" ORDER BY received_at DESC, audit_id DESC LIMIT ?");
        parameters.add(maxRows);
        return jdbc.query(sql.toString(), mapper(), parameters.toArray());
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Stored envelope is not valid JSON", e);
        }
    }
}
