package dk.ek.shift_happens.leave.domain;

import java.util.Optional;

/** Port for leave types. The adapter lives in infrastructure/persistence. */
public interface LeaveTypeRepository {

    LeaveType save(LeaveType leaveType);

    /** Also finds soft-deleted types: existing requests and entries still need the name. */
    Optional<LeaveType> findById(long leaveTypeId);

    /** Like {@link #findById}, but a soft-deleted type is treated as unknown. */
    Optional<LeaveType> findActiveById(long leaveTypeId);

    /** The row is locked until the transaction ends (PUT and DELETE), so versions of one type never collide. */
    Optional<LeaveType> findByIdForUpdate(long leaveTypeId);

    /** Case-insensitive among the types that are not deleted, optionally ignoring one type (the one being updated). */
    boolean existsByNameExcluding(String leaveTypeName, Long excludedLeaveTypeId);
}
