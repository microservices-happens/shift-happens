package dk.ek.shift_happens.leave.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/** Request body of POST /v1/leaverequests (contracts/openapi/leave.yaml, schema LeaveRequest). */
public record CreateLeaveRequest(
        @NotNull @Positive Long employeeId,
        @NotNull @Positive Long leaveTypeId,
        @NotNull LocalDate startDate,
        @NotNull LocalDate endDate,
        String reason) {
}
