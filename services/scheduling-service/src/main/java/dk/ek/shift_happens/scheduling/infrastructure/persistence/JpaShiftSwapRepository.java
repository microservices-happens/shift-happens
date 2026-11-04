package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.ShiftSwap;
import dk.ek.shift_happens.scheduling.domain.ShiftSwapRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapter: swaps on top of Spring Data JPA. The decision is joined in from shift_swap_approval. */
@Repository
@Profile("command")
class JpaShiftSwapRepository implements ShiftSwapRepository {

    private final ShiftSwapJpaRepository jpa;
    private final ShiftSwapApprovalJpaRepository approvals;

    JpaShiftSwapRepository(ShiftSwapJpaRepository jpa, ShiftSwapApprovalJpaRepository approvals) {
        this.jpa = jpa;
        this.approvals = approvals;
    }

    @Override
    public ShiftSwap save(ShiftSwap swap) {
        return withDecision(jpa.save(ShiftSwapEntity.from(swap)));
    }

    @Override
    public Optional<ShiftSwap> findById(long shiftSwapId) {
        return jpa.findById(shiftSwapId).map(this::withDecision);
    }

    @Override
    public Optional<ShiftSwap> findPendingForAssignment(long shiftAssignmentId) {
        return jpa.findByOriginalShiftAssignmentIdAndSwapStatus(shiftAssignmentId, "PENDING")
                .map(this::withDecision);
    }

    @Override
    public void delete(ShiftSwap swap) {
        jpa.deleteById(swap.shiftSwapId());
    }

    private ShiftSwap withDecision(ShiftSwapEntity entity) {
        return entity.toDomain(approvals.findByShiftSwapId(entity.getShiftSwapId())
                .map(ShiftSwapApprovalEntity::toSwapDecision)
                .orElse(null));
    }
}
