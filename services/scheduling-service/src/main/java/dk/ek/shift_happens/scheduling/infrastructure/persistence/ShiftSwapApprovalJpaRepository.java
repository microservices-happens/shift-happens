package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repository for shift_swap_approval. */
interface ShiftSwapApprovalJpaRepository extends JpaRepository<ShiftSwapApprovalEntity, Long> {

    Optional<ShiftSwapApprovalEntity> findByShiftSwapId(long shiftSwapId);
}
