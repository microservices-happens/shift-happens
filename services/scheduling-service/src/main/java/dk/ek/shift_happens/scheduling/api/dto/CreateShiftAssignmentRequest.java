package dk.ek.shift_happens.scheduling.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** Request body of POST /v1/shiftassignments (contracts/openapi/scheduling.yaml). */
public record CreateShiftAssignmentRequest(
        @Positive long shiftId,
        @Positive long employeeId,
        @Pattern(regexp = "ASSIGNED", message = "a new assignment starts as ASSIGNED") String assignmentStatus) {
}
