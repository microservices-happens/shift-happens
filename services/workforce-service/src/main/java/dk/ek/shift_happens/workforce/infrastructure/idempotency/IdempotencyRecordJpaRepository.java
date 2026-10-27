package dk.ek.shift_happens.workforce.infrastructure.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordEntity, String> {
}
