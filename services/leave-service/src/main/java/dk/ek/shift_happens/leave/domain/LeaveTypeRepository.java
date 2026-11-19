package dk.ek.shift_happens.leave.domain;

import java.util.Optional;

/** Port for leave types. The adapter lives in infrastructure/persistence. */
public interface LeaveTypeRepository {

    LeaveType save(LeaveType leaveType);

    Optional<LeaveType> findById(long leaveTypeId);

    /** Case-insensitive, optionally ignoring one type (the one being updated). */
    boolean existsByNameExcluding(String leaveTypeName, Long excludedLeaveTypeId);

    void delete(long leaveTypeId);
}
