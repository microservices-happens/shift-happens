package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repository for shift_approval. */
interface ShiftApprovalJpaRepository extends JpaRepository<ShiftApprovalEntity, Long> {

    Optional<ShiftApprovalEntity> findByShiftAssignmentId(long shiftAssignmentId);
}
