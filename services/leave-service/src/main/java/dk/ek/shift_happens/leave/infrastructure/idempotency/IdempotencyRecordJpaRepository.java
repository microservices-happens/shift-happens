package dk.ek.shift_happens.leave.infrastructure.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for idempotency_record. */
interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordEntity, String> {

    /** Transaction-scoped advisory lock, two-int form (the one-argument form is the employee calendar lock). */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:classId, :keyHash) AS text)", nativeQuery = true)
    String lock(@Param("classId") int classId, @Param("keyHash") int keyHash);
}
