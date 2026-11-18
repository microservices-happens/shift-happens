package dk.ek.shift_happens.leave.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** Spring Data repository for leave_request. */
interface LeaveRequestJpaRepository extends JpaRepository<LeaveRequestEntity, Long> {

    List<LeaveRequestEntity> findByEmployeeIdAndRequestStatusIn(long employeeId, Collection<String> statuses);

    boolean existsByLeaveTypeId(long leaveTypeId);
}
