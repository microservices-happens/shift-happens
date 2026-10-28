package dk.ek.shift_happens.scheduling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.domain.EmployeeReplica;
import dk.ek.shift_happens.scheduling.domain.EmployeeReplicaRepository;
import dk.ek.shift_happens.scheduling.domain.EmployeeSnapshot;
import dk.ek.shift_happens.scheduling.domain.ProcessedEventLog;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Projects workforce.employee.* events into the local replica (P2.8), so a qualification check never
 * calls another service.
 *
 * <p>One transaction: the replica row and the processed-event marker commit together, so a redelivered
 * event can never cause a second side effect (idempotent consumer). A snapshot is only applied when its
 * aggregateVersion is higher than the stored one, and an existing tombstone is never cleared
 * (commutative consumer + tombstones win, contracts/README.md).
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

    @Transactional
    public void apply(EventEnvelope envelope) {
        if (processedEvents.contains(envelope.eventId())) {
            log.debug("Ignoring already processed event {}", envelope.eventId());
            return;
        }

        EmployeeReplica current = employees.findByEmployeeId(envelope.aggregateId()).orElse(null);

        // A tombstone carries {entityType, entityId, deletedAt}, not an employee snapshot.
        if (envelope.eventType().endsWith(".deleted.v1")) {
            if (current != null && current.accepts(envelope.aggregateVersion())) {
                employees.save(new EmployeeReplica(current.employeeId(), current.firstName(),
                        current.lastName(), current.employmentStatus(), current.jobRoleIds(),
                        current.primaryWorkLocationId(), envelope.aggregateVersion(), envelope.occurredAt()));
            }
            processedEvents.record(envelope.eventId());
            return;
        }

        EmployeeSnapshot snapshot = objectMapper.convertValue(envelope.data(), EmployeeSnapshot.class);
        if (snapshot.employeeId() == null) {
            throw new IllegalArgumentException("event " + envelope.eventId() + " has no employeeId");
        }

        if (current != null && !current.accepts(envelope.aggregateVersion())) {
            log.info("Ignoring {} for employee {}: version {} is not newer than {}", envelope.eventType(),
                    snapshot.employeeId(), envelope.aggregateVersion(), current.aggregateVersion());
            processedEvents.record(envelope.eventId());
            return;
        }

        employees.save(new EmployeeReplica(
                snapshot.employeeId(),
                snapshot.firstName(),
                snapshot.lastName(),
                snapshot.employmentStatus(),
                snapshot.jobRoleIds() == null ? List.of() : snapshot.jobRoleIds(),
                snapshot.primaryWorkLocationId(),
                envelope.aggregateVersion(),
                // A snapshot never clears a tombstone that is already there.
                current == null ? null : current.deletedAt()));
        processedEvents.record(envelope.eventId());
    }
}
