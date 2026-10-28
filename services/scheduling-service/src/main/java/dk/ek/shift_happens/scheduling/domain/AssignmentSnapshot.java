package dk.ek.shift_happens.scheduling.domain;

/**
 * data of scheduling.assignment.created.v1 / .updated.v1, matching
 * contracts/events/payloads/assignment.schema.json. The shift window is copied in so a consumer can
 * show an assignment without looking up the shift.
 */
public record AssignmentSnapshot(
        long shiftAssignmentId,
        long shiftId,
        long employeeId,
        String assignmentStatus,
        java.time.Instant assignedDatetime,
        java.time.Instant checkInDatetime,
        java.time.Instant checkOutDatetime,
        java.time.Instant shiftStartDatetime,
        java.time.Instant shiftEndDatetime) {
}
