package dk.ek.shift_happens.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.audit.domain.AuditEntry;
import dk.ek.shift_happens.audit.infrastructure.persistence.AuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/** Append a consumed event and answer the Administrator's search (contracts/openapi/audit.yaml). */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditRepository repository;
    private final int maxRows;

    public AuditLogService(AuditRepository repository, @Value("${app.query.max-rows}") int maxRows) {
        this.repository = repository;
        this.maxRows = maxRows;
    }

    @Transactional
    public void append(JsonNode envelope) {
        if (!envelope.hasNonNull("eventId") || !envelope.hasNonNull("eventType")
                || !envelope.hasNonNull("occurredAt") || !envelope.hasNonNull("producer")) {
            throw new IllegalArgumentException("Event is missing an envelope field");
        }
        if (repository.append(envelope)) {
            log.debug("Appended {} {}", envelope.path("eventType").asText(), envelope.path("eventId").asText());
        }
    }

    public List<AuditEntry> search(String eventType, Long aggregateId, String correlationId,
                                   OffsetDateTime from, OffsetDateTime to) {
        return repository.search(eventType, aggregateId, correlationId, from, to, maxRows);
    }
}
