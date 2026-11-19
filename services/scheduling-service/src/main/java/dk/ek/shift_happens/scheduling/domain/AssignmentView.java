package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;

/**
 * An assignment as the read model keeps it, built from scheduling.assignment.created/updated.v1. The
 * shift window is copied in, exactly as the event contract promises, so the schedule can be filtered
 * without loading the shift first.
 */
public record AssignmentView(
        long shiftAssignmentId,
        long shiftId,
        long employeeId,
        AssignmentStatus status,
        Instant assignedDatetime,
        Instant checkInDatetime,
        Instant checkOutDatetime,
        Instant shiftStartDatetime,
        Instant shiftEndDatetime,
        long aggregateVersion,
        Instant deletedAt) {

    public boolean deleted() {
        return deletedAt != null;
    }

    /** Counts towards staffing and towards an overlap: ASSIGNED, APPROVED or SWAPPED. */
    public boolean active() {
        return !deleted() && status.isActive();
    }

    public AssignmentView tombstoned(long version, Instant deletedAt) {
        return new AssignmentView(shiftAssignmentId, shiftId, employeeId, status, assignedDatetime,
                checkInDatetime, checkOutDatetime, shiftStartDatetime, shiftEndDatetime, version, deletedAt);
    }
}
