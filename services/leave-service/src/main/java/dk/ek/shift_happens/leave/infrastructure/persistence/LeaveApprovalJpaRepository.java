package dk.ek.shift_happens.leave.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repository for leave_approval. */
interface LeaveApprovalJpaRepository extends JpaRepository<LeaveApprovalEntity, Long> {

    /** The latest decision about a request. */
    Optional<LeaveApprovalEntity> findFirstByLeaveRequestIdOrderByLeaveApprovalIdDesc(long leaveRequestId);
}
