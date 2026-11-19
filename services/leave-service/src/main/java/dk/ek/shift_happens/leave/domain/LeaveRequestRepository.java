package dk.ek.shift_happens.leave.domain;

import java.util.List;
import java.util.Optional;

/** Port for leave requests. The decision is joined in from leave_approval by the adapter. */
public interface LeaveRequestRepository {

    LeaveRequest save(LeaveRequest request);

    Optional<LeaveRequest> findById(long leaveRequestId);

    /**
     * Like {@link #findById} but takes a row lock held until the transaction ends, so edit, cancel and reject of
     * the same request are serialized and each one sees the committed result of the previous one.
     */
    Optional<LeaveRequest> findByIdForUpdate(long leaveRequestId);

    /**
     * Serializes every change to one employee's calendar until the transaction ends, so the application's overlap
     * check cannot race with a concurrent request of the same employee.
     */
    void lockEmployeeCalendar(long employeeId);

    /** The employee's requests that occupy the calendar (PENDING, APPROVAL_PENDING, APPROVED). */
    List<LeaveRequest> findOccupyingByEmployeeId(long employeeId);

    boolean existsByLeaveTypeId(long leaveTypeId);
}
