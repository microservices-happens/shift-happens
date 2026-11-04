package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Port for assignments. The adapter lives in infrastructure/persistence. */
public interface ShiftAssignmentRepository {

    ShiftAssignment save(ShiftAssignment assignment);

    Optional<ShiftAssignment> findById(long shiftAssignmentId);

    /** Active assignments of the employee whose shift overlaps [from, to). */
    List<ShiftAssignment> findActiveOverlapping(long employeeId, Instant from, Instant to);

    /** Active assignments on one shift, for the staffing count of the understaffed event. */
    List<ShiftAssignment> findActiveByShift(long shiftId);

    /** Active assignments overlapping [from, to) that are not on this shift (who is already busy). */
    List<ShiftAssignment> findActiveOverlappingOtherThanShift(long shiftId, Instant from, Instant to);

    /** Shift windows the employee already works inside [from, to), for hoursThisWeek. */
    List<WorkedWindow> findWorkedWindows(long employeeId, Instant from, Instant to);
}
