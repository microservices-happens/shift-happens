package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.RequiredJobRole;

/** One entry of requiredJobRoles in a response (contracts/openapi/scheduling.yaml). */
public record RequiredJobRoleResponse(long jobRoleId, int requiredEmployeeCount) {

    public static RequiredJobRoleResponse from(RequiredJobRole requiredJobRole) {
        return new RequiredJobRoleResponse(requiredJobRole.jobRoleId(), requiredJobRole.requiredEmployeeCount());
    }
}
