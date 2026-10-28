package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.RequiredJobRole;

/** Response body of the required job role endpoints (contracts/openapi/scheduling.yaml). */
public record ShiftRequiredJobRoleResponse(
        Long shiftRequiredJobRoleId,
        long shiftId,
        long jobRoleId,
        int requiredEmployeeCount) {

    public static ShiftRequiredJobRoleResponse from(long shiftId, RequiredJobRole requiredJobRole) {
        return new ShiftRequiredJobRoleResponse(requiredJobRole.shiftRequiredJobRoleId(), shiftId,
                requiredJobRole.jobRoleId(), requiredJobRole.requiredEmployeeCount());
    }
}
