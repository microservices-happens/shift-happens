package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.ShiftApproval;
import dk.ek.shift_happens.scheduling.domain.ShiftApprovalRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapter: approvals on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaShiftApprovalRepository implements ShiftApprovalRepository {

    private final ShiftApprovalJpaRepository jpa;

    JpaShiftApprovalRepository(ShiftApprovalJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ShiftApproval save(ShiftApproval approval) {
        return jpa.save(ShiftApprovalEntity.from(approval)).toDomain();
    }

    @Override
    public Optional<ShiftApproval> findById(long shiftApprovalId) {
        return jpa.findById(shiftApprovalId).map(ShiftApprovalEntity::toDomain);
    }

    @Override
    public Optional<ShiftApproval> findByShiftAssignmentId(long shiftAssignmentId) {
        return jpa.findByShiftAssignmentId(shiftAssignmentId).map(ShiftApprovalEntity::toDomain);
    }

    @Override
    public void delete(ShiftApproval approval) {
        jpa.deleteById(approval.shiftApprovalId());
    }
}
