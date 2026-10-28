package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;

/**
 * An employee's assignment to a shift, as Scheduling owns it.
 *
 * <p>The overlap and qualification rules are checked by the use case before this is created; this
 * record keeps the lifecycle rules (which status may follow which) in one testable place.
 */
public record ShiftAssignment(
        Long shiftAssignmentId,
        long shiftId,
        long employeeId,
        AssignmentStatus status,
        Instant assignedDatetime,
        Instant checkInDatetime,
        Instant checkOutDatetime,
        long aggregateVersion,
        boolean deleted) {

    public static ShiftAssignment create(long shiftId, long employeeId, Instant assignedAt) {
        return new ShiftAssignment(null, shiftId, employeeId, AssignmentStatus.ASSIGNED, assignedAt,
                null, null, 1, false);
    }

    /** Status change, including check-in/check-out when the caller reports them. */
    public ShiftAssignment withStatus(AssignmentStatus target, Instant checkIn, Instant checkOut) {
        if (deleted) {
            throw new IllegalStateException("a deleted assignment cannot be changed");
        }
        if (!status.canTransitionTo(target)) {
            throw new AssignmentRejectedException(
                    "assignment " + shiftAssignmentId + " cannot change status from " + status + " to " + target);
        }
        return new ShiftAssignment(shiftAssignmentId, shiftId, employeeId, target, assignedDatetime,
                checkIn == null ? checkInDatetime : checkIn,
                checkOut == null ? checkOutDatetime : checkOut,
                aggregateVersion + 1, false);
    }

    /** Soft delete (DELETE /v1/shiftassignments/{id}): version moves on, the event is a tombstone. */
    public ShiftAssignment markedDeleted() {
        if (deleted) {
            throw new IllegalStateException("the assignment is already deleted");
        }
        return new ShiftAssignment(shiftAssignmentId, shiftId, employeeId, status, assignedDatetime,
                checkInDatetime, checkOutDatetime, aggregateVersion + 1, true);
    }

    /** data of scheduling.assignment.created/updated.v1; the shift window is copied in. */
    public AssignmentSnapshot toSnapshot(Shift shift) {
        if (shiftAssignmentId == null) {
            throw new IllegalStateException("an assignment must be saved before its snapshot can be published");
        }
        return new AssignmentSnapshot(shiftAssignmentId, shiftId, employeeId, status.name(),
                assignedDatetime, checkInDatetime, checkOutDatetime,
                shift.startDatetime(), shift.endDatetime());
    }
}
