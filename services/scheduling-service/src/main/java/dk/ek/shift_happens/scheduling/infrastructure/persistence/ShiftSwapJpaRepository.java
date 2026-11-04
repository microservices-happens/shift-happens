package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repository for shift_swap. */
interface ShiftSwapJpaRepository extends JpaRepository<ShiftSwapEntity, Long> {

    Optional<ShiftSwapEntity> findByOriginalShiftAssignmentIdAndSwapStatus(long shiftAssignmentId,
                                                                         String swapStatus);
}
