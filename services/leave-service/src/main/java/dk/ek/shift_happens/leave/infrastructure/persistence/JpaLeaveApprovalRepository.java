package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.LeaveApproval;
import dk.ek.shift_happens.leave.domain.LeaveApprovalRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapter: leave approvals on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaLeaveApprovalRepository implements LeaveApprovalRepository {

    private final LeaveApprovalJpaRepository jpa;

    JpaLeaveApprovalRepository(LeaveApprovalJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public LeaveApproval save(LeaveApproval approval) {
        return jpa.save(LeaveApprovalEntity.from(approval)).toDomain();
    }

    @Override
    public Optional<LeaveApproval> findById(long leaveApprovalId) {
        return jpa.findById(leaveApprovalId).map(LeaveApprovalEntity::toDomain);
    }
}
