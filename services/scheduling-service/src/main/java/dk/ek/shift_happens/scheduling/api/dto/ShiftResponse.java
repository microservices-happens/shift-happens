package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.Shift;

import java.time.Instant;
import java.util.List;

/** Response body of the shift endpoints (contracts/openapi/scheduling.yaml, schema Shift). */
public record ShiftResponse(
        Long shiftId,
        long departmentId,
        long workLocationId,
        String shiftName,
        Instant startDatetime,
        Instant endDatetime,
        String shiftStatus,
        List<RequiredJobRoleResponse> requiredJobRoles) {

    public static ShiftResponse from(Shift shift) {
        return new ShiftResponse(
                shift.shiftId(),
                shift.departmentId(),
                shift.workLocationId(),
                shift.shiftName(),
                shift.startDatetime(),
                shift.endDatetime(),
                shift.status().name(),
                shift.requiredJobRoles().stream().map(RequiredJobRoleResponse::from).toList());
    }
}
