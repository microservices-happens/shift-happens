package dk.ek.shift_happens.leave.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.leave.domain.EmployeeReplica;
import dk.ek.shift_happens.leave.domain.EmployeeReplicaRepository;
import dk.ek.shift_happens.leave.domain.EmployeeSnapshot;
import dk.ek.shift_happens.leave.domain.ProcessedEventLog;
import dk.ek.shift_happens.leave.infrastructure.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.function.ToLongFunction;

/**
 * Projects workforce.employee.* events into the local replica, so validating a leave request never
 * calls another service.
 *
 * <p>One transaction: the replica row and the processed-event marker commit together, so a redelivered
 * event can never cause a second side effect (idempotent consumer). The version/tombstone decision is
 * made by {@link EmployeeReplica}; this class only translates the envelope.
 */
@Service
@Profile("command")
public class EmployeeReplicaService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeReplicaService.class);

    private final EmployeeReplicaRepository employees;
    private final ProcessedEventLog processedEvents;
    private final ObjectMapper objectMapper;

    EmployeeReplicaService(EmployeeReplicaRepository employees, ProcessedEventLog processedEvents,
                           ObjectMapper objectMapper) {
        this.employees = employees;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    /** workforce.employee.created.v1 and .updated.v1: the data is a full employee snapshot. */
    @Transactional
    public void applySnapshot(EventEnvelope envelope) {
        applyOnce(envelope, this::projectSnapshot);
    }

    /** workforce.employee.deleted.v1: the data is a tombstone. */
    @Transactional
    public void applyTombstone(EventEnvelope envelope) {
        applyOnce(envelope, this::projectTombstone);
    }

    private void applyOnce(EventEnvelope envelope, ToLongFunction<EventEnvelope> projection) {
        if (processedEvents.contains(envelope.eventId())) {
            log.debug("Ignoring already processed event {}", envelope.eventId());
            return;
        }
        long employeeId = projection.applyAsLong(envelope);
        log.debug("Applied {} for employee {}", envelope.eventType(), employeeId);
        processedEvents.record(envelope.eventId());
    }

    private long projectSnapshot(EventEnvelope envelope) {
        EmployeeSnapshot snapshot = objectMapper.convertValue(envelope.data(), EmployeeSnapshot.class);
        if (snapshot == null || snapshot.employeeId() == null) {
            throw new IllegalArgumentException("event " + envelope.eventId() + " has no employeeId");
        }
        EmployeeReplica current = employees.findByEmployeeId(snapshot.employeeId()).orElse(null);
        Optional<EmployeeReplica> next = EmployeeReplica.applySnapshot(current, snapshot, envelope.aggregateVersion());
        if (next.isPresent()) {
            employees.save(next.get());
        } else {
            log.info("Ignoring {} for employee {}: version {} is not newer than {}", envelope.eventType(),
                    snapshot.employeeId(), envelope.aggregateVersion(), current.aggregateVersion());
        }
        return snapshot.employeeId();
    }

    /** A tombstone carries {entityType, entityId, deletedAt}, not an employee snapshot. */
    private long projectTombstone(EventEnvelope envelope) {
        JsonNode data = objectMapper.valueToTree(envelope.data());
        JsonNode entityId = data.path("entityId");
        if (!entityId.canConvertToLong()) {
            throw new IllegalArgumentException("event " + envelope.eventId() + " has no entityId");
        }
        long employeeId = entityId.asLong();
        JsonNode deletedAtNode = data.path("deletedAt");
        Instant deletedAt = deletedAtNode.isTextual() ? Instant.parse(deletedAtNode.asText()) : envelope.occurredAt();

        EmployeeReplica current = employees.findByEmployeeId(employeeId).orElse(null);
        EmployeeReplica.applyTombstone(current, employeeId, envelope.aggregateVersion(), deletedAt)
                .ifPresent(employees::save);
        return employeeId;
    }
}
