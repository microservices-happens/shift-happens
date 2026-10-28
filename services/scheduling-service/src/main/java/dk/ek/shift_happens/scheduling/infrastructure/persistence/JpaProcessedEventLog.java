package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.ProcessedEventLog;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/** Adapter: recorded in the same transaction as the projection change, so a redelivery is a no-op. */
@Component
@Profile("command")
class JpaProcessedEventLog implements ProcessedEventLog {

    private final ProcessedEventJpaRepository jpa;

    JpaProcessedEventLog(ProcessedEventJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public boolean contains(UUID eventId) {
        return jpa.existsById(eventId);
    }

    @Override
    public void record(UUID eventId) {
        jpa.save(new ProcessedEventEntity(eventId, Instant.now()));
    }
}
