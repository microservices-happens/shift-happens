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
}
