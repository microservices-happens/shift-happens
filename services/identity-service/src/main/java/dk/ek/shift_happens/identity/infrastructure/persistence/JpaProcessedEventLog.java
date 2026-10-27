package dk.ek.shift_happens.identity.infrastructure.persistence;

import dk.ek.shift_happens.identity.domain.ProcessedEventLog;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
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
        jpa.save(new ProcessedEventEntity(eventId));
    }
}
