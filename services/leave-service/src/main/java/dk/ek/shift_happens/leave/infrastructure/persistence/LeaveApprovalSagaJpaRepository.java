package dk.ek.shift_happens.leave.infrastructure.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repository for leave_approval_saga. */
interface LeaveApprovalSagaJpaRepository extends JpaRepository<LeaveApprovalSagaEntity, Long> {

    Optional<LeaveApprovalSagaEntity> findByApprovalStartedEventId(UUID approvalStartedEventId);

    Optional<LeaveApprovalSagaEntity> findFirstByLeaveRequestIdOrderByAttemptDesc(long leaveRequestId);

    Optional<LeaveApprovalSagaEntity> findFirstByLeaveRequestIdAndStatus(long leaveRequestId, String status);

    @Query("select s from LeaveApprovalSagaEntity s where s.status = 'STARTED' and s.deadline < :now "
            + "order by s.deadline")
    List<LeaveApprovalSagaEntity> findOverdue(@Param("now") Instant now, Pageable page);
}
