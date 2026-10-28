package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.RequiredJobRole;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

/** Request body of PUT /v1/shiftrequiredjobroles/{id} (contracts/openapi/scheduling.yaml). */
public record UpdateRequiredJobRoleRequest(
        @Positive long shiftId,
        @Positive long jobRoleId,
        @Min(value = 1, message = "must be at least 1") int requiredEmployeeCount) {

    public RequiredJobRole toRequiredJobRole() {
        return new RequiredJobRole(jobRoleId, requiredEmployeeCount);
    }
}
