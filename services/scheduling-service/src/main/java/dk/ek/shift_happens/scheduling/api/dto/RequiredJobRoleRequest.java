package dk.ek.shift_happens.scheduling.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/** One entry of requiredJobRoles in the request body (contracts/openapi/scheduling.yaml). */
public record RequiredJobRoleRequest(
        @Positive long jobRoleId,
        @Min(value = 1, message = "must be at least 1") int requiredEmployeeCount) {
}
