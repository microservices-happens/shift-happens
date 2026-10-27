package dk.ek.shift_happens.identity.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEventEntity, UUID> {
}
