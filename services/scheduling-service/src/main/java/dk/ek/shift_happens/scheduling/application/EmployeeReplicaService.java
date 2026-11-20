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

import java.util.Optional;

/**
 * Projects workforce.employee.* events into the local replica (P2.8), so a qualification check never
 * calls another service.
 *
 * <p>One transaction: the replica row and the processed-event marker commit together, so a redelivered
 * event can never cause a second side effect (idempotent consumer). The version/tombstone decision is
 * made by {@link EmployeeReplica}: the highest version wins, a tombstone is never cleared and is kept
 * even when it arrives before the create, so the row does not depend on the arrival order
 * (commutative consumer + tombstones win, contracts/README.md; {@code EmployeeReplicaShuffledOrderTest}).
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
            EmployeeReplica.applyTombstone(current, envelope.aggregateId(), envelope.aggregateVersion(),
                    envelope.occurredAt()).ifPresent(employees::save);
            processedEvents.record(envelope.eventId());
            return;
        }

        EmployeeSnapshot snapshot = objectMapper.convertValue(envelope.data(), EmployeeSnapshot.class);
        if (snapshot.employeeId() == null) {
            throw new IllegalArgumentException("event " + envelope.eventId() + " has no employeeId");
        }

        Optional<EmployeeReplica> next = EmployeeReplica.applySnapshot(current, snapshot,
                envelope.aggregateVersion());
        if (next.isPresent()) {
            employees.save(next.get());
        } else {
            log.info("Ignoring {} for employee {}: version {} is not newer than the stored snapshot {}",
                    envelope.eventType(), snapshot.employeeId(), envelope.aggregateVersion(),
                    current.snapshotVersion());
        }
        processedEvents.record(envelope.eventId());
    }
}
