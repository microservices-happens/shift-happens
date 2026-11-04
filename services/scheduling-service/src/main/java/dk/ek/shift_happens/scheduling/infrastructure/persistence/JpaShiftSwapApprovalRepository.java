package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.ShiftSwapApproval;
import dk.ek.shift_happens.scheduling.domain.ShiftSwapApprovalRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapter: swap approvals on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaShiftSwapApprovalRepository implements ShiftSwapApprovalRepository {

    private final ShiftSwapApprovalJpaRepository jpa;

    JpaShiftSwapApprovalRepository(ShiftSwapApprovalJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ShiftSwapApproval save(ShiftSwapApproval approval) {
        return jpa.save(ShiftSwapApprovalEntity.from(approval)).toDomain();
    }

    @Override
    public Optional<ShiftSwapApproval> findByShiftSwapId(long shiftSwapId) {
        return jpa.findByShiftSwapId(shiftSwapId).map(ShiftSwapApprovalEntity::toDomain);
    }
}
