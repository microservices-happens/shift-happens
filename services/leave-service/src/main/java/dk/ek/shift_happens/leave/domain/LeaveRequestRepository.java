package dk.ek.shift_happens.leave.domain;

import java.util.List;
import java.util.Optional;

/** Port for leave requests. The decision is joined in from leave_approval by the adapter. */
public interface LeaveRequestRepository {

    LeaveRequest save(LeaveRequest request);

    Optional<LeaveRequest> findById(long leaveRequestId);

    /** The employee's requests that occupy the calendar (PENDING, APPROVAL_PENDING, APPROVED). */
    List<LeaveRequest> findOccupyingByEmployeeId(long employeeId);

    boolean existsByLeaveTypeId(long leaveTypeId);
}
