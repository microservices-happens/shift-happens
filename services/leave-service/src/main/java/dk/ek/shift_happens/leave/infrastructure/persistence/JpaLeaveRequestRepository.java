package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.LeaveRequest;
import dk.ek.shift_happens.leave.domain.LeaveRequestRepository;
import dk.ek.shift_happens.leave.domain.LeaveRequestStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Adapter: leave requests on top of Spring Data JPA. The decision is joined in from leave_approval. */
@Repository
@Profile("command")
class JpaLeaveRequestRepository implements LeaveRequestRepository {

    private final LeaveRequestJpaRepository jpa;
    private final LeaveApprovalJpaRepository approvals;

    JpaLeaveRequestRepository(LeaveRequestJpaRepository jpa, LeaveApprovalJpaRepository approvals) {
        this.jpa = jpa;
        this.approvals = approvals;
    }

    /**
     * Flushes so a violation of the DB-level overlap guard (a concurrent request) surfaces here, inside
     * the transaction, and not at commit.
     */
    @Override
    public LeaveRequest save(LeaveRequest request) {
        return withDecision(jpa.saveAndFlush(LeaveRequestEntity.from(request)));
    }

    @Override
    public Optional<LeaveRequest> findById(long leaveRequestId) {
        return jpa.findById(leaveRequestId).map(this::withDecision);
    }

    @Override
    public Optional<LeaveRequest> findByIdForUpdate(long leaveRequestId) {
        return jpa.findForUpdate(leaveRequestId).map(this::withDecision);
    }

    @Override
    public Optional<LeaveRequest> findByIdForUpdateSkipLocked(long leaveRequestId) {
        return jpa.findForUpdateSkipLocked(leaveRequestId).map(this::withDecision);
    }

    @Override
    public void lockEmployeeCalendar(long employeeId) {
        jpa.lockEmployeeCalendar(employeeId);
    }

    @Override
    public List<LeaveRequest> findOccupyingByEmployeeId(long employeeId) {
        return jpa.findByEmployeeIdAndRequestStatusIn(employeeId,
                        LeaveRequestStatus.OCCUPYING.stream().map(Enum::name).toList())
                .stream().map(this::withDecision).toList();
    }

    @Override
    public boolean existsByLeaveTypeId(long leaveTypeId) {
        return jpa.existsByLeaveTypeId(leaveTypeId);
    }

    private LeaveRequest withDecision(LeaveRequestEntity entity) {
        return entity.toDomain(approvals.findFirstByLeaveRequestIdOrderByLeaveApprovalIdDesc(entity.getLeaveRequestId())
                .map(LeaveApprovalEntity::toLeaveDecision)
                .orElse(null));
    }
}
