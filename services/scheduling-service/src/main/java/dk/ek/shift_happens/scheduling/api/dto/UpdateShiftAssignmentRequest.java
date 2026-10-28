package dk.ek.shift_happens.scheduling.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.Instant;

/**
 * Request body of PUT /v1/shiftassignments/{id} (contracts/openapi/scheduling.yaml).
 *
 * <p>shiftId and employeeId belong to the contract body but cannot be changed by an update: moving an
 * assignment to another employee is a shift swap (P2.9), and a wrong id is rejected.
 */
public record UpdateShiftAssignmentRequest(
        @Positive long shiftId,
        @Positive long employeeId,
        @NotNull @Pattern(regexp = "ASSIGNED|APPROVED|CANCELLED|SWAPPED|RELEASED_FOR_LEAVE",
                message = "must be ASSIGNED, APPROVED, CANCELLED, SWAPPED or RELEASED_FOR_LEAVE")
        String assignmentStatus,
        Instant checkInDatetime,
        Instant checkOutDatetime) {
}
