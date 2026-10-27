package dk.ek.shift_happens.identity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One handled event id: a redelivered event is ignored (idempotent consumer). */
@Entity
@Table(name = "processed_events")
public class ProcessedEventEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedEventEntity() {
        // for JPA
    }

    ProcessedEventEntity(UUID eventId) {
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }
}
