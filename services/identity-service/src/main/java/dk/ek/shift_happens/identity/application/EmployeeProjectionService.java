package dk.ek.shift_happens.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import dk.ek.shift_happens.identity.domain.EmployeeSnapshot;
import dk.ek.shift_happens.identity.domain.ProcessedEventLog;
import dk.ek.shift_happens.identity.infrastructure.messaging.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Projects workforce.employee.* events into the local account table.
 *
 * <p>One transaction: the account upsert and the processed-event marker commit together, so a
 * redelivered event can never cause a second side effect (idempotent consumer) and an out-of-order
 * event never overwrites a newer state (commutative handler).
 */
@Service
public class EmployeeProjectionService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeProjectionService.class);

    private final AccountRepository accounts;
    private final ProcessedEventLog processedEvents;
    private final ObjectMapper objectMapper;

    EmployeeProjectionService(AccountRepository accounts, ProcessedEventLog processedEvents,
                              ObjectMapper objectMapper) {
        this.accounts = accounts;
        this.processedEvents = processedEvents;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void apply(EventEnvelope envelope) {
        if (processedEvents.contains(envelope.eventId())) {
            log.debug("Ignoring already processed event {}", envelope.eventId());
            return;
        }

        // A tombstone carries {entityType, entityId, deletedAt}, not an employee snapshot
        // (contracts/events/payloads/tombstone.schema.json), so it is handled on its own.
        if (envelope.eventType().endsWith(".deleted.v1")) {
            accounts.findByEmployeeId(envelope.aggregateId())
                    .filter(account -> account.accepts(envelope.aggregateVersion()))
                    // Keep the tombstone: the account is disabled, never deleted, so a late update
                    // cannot bring a deleted employee back to life.
                    .ifPresent(account -> accounts.save(account.deleted(envelope.aggregateVersion())));
            processedEvents.record(envelope.eventId());
            return;
        }

        EmployeeSnapshot snapshot = objectMapper.convertValue(envelope.data(), EmployeeSnapshot.class);
        if (snapshot.employeeId() == null) {
            throw new IllegalArgumentException("Event " + envelope.eventId() + " has no employeeId");
        }

        long version = envelope.aggregateVersion();
        Optional<Account> current = accounts.findByEmployeeId(snapshot.employeeId());

        if (current.isPresent() && !current.get().accepts(version)) {
            log.info("Ignoring {} for employee {}: version {} is not newer than {}",
                    envelope.eventType(), snapshot.employeeId(), version, current.get().aggregateVersion());
        } else {
            accounts.save(current
                    .map(account -> account.withSnapshot(snapshot, version))
                    .orElseGet(() -> Account.fromSnapshot(snapshot, version)));
        }

        processedEvents.record(envelope.eventId());
    }
}
