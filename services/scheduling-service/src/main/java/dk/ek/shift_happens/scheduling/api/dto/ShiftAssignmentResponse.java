package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.ShiftAssignment;

import java.time.Instant;

/** Response body of the assignment endpoints (contracts/openapi/scheduling.yaml). */
public record ShiftAssignmentResponse(
        Long shiftAssignmentId,
        long shiftId,
        long employeeId,
        String assignmentStatus,
        Instant assignedDatetime,
        Instant checkInDatetime,
        Instant checkOutDatetime) {

    public static ShiftAssignmentResponse from(ShiftAssignment assignment) {
        return new ShiftAssignmentResponse(assignment.shiftAssignmentId(), assignment.shiftId(),
                assignment.employeeId(), assignment.status().name(), assignment.assignedDatetime(),
                assignment.checkInDatetime(), assignment.checkOutDatetime());
    }
}
