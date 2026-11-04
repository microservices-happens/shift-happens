package dk.ek.shift_happens.scheduling.api.dto;

import jakarta.validation.constraints.Positive;

/** Request body of PUT /v1/shiftswaps/{id}: another colleague or reason while pending. */
public record UpdateShiftSwapRequest(
        @Positive long originalShiftAssignmentId,
        @Positive long employeeFromId,
        @Positive long employeeToId,
        String reason) {
}
