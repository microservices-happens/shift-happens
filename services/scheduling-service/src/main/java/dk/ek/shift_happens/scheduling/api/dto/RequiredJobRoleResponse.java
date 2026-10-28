package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.RequiredJobRole;

/** One entry of requiredJobRoles inside a shift response (contracts/openapi/scheduling.yaml). */
public record RequiredJobRoleResponse(Long shiftRequiredJobRoleId, long jobRoleId, int requiredEmployeeCount) {

    public static RequiredJobRoleResponse from(RequiredJobRole requiredJobRole) {
        return new RequiredJobRoleResponse(requiredJobRole.shiftRequiredJobRoleId(),
                requiredJobRole.jobRoleId(), requiredJobRole.requiredEmployeeCount());
    }
}
