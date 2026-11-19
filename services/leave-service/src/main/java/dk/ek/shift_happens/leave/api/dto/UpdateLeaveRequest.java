package dk.ek.shift_happens.leave.api.dto;

import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

/**
 * Request body of PATCH /v1/leaverequests/{id}. Every field is optional: an omitted (or null) field keeps its
 * current value. The employee of a request cannot change.
 */
public record UpdateLeaveRequest(
        @Positive Long leaveTypeId,
        LocalDate startDate,
        LocalDate endDate,
        String reason) {
}
