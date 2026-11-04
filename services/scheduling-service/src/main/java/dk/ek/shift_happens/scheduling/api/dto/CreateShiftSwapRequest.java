package dk.ek.shift_happens.scheduling.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** Request body of POST /v1/shiftswaps (contracts/openapi/scheduling.yaml, schema ShiftSwap). */
public record CreateShiftSwapRequest(
        @Positive long originalShiftAssignmentId,
        @Positive long employeeFromId,
        @Positive long employeeToId,
        String reason) {
}
