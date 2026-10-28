package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data repository for processed_events. */
interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEventEntity, UUID> {
}
