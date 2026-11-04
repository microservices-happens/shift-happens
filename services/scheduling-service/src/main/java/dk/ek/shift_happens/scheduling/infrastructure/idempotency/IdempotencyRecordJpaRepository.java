package dk.ek.shift_happens.scheduling.infrastructure.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for idempotency_record. */
interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordEntity, String> {
}
