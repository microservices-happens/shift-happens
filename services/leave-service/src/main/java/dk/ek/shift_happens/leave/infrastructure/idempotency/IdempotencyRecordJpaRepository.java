package dk.ek.shift_happens.leave.infrastructure.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for idempotency_record. */
interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordEntity, String> {
}
