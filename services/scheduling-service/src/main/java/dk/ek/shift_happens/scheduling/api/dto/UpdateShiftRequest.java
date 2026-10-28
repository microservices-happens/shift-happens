package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.NewShift;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRole;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.Instant;
import java.util.List;

/**
 * Request body of PUT /v1/shifts/{id} (contracts/openapi/scheduling.yaml, schema Shift).
 *
 * <p>A PUT replaces the shift, so every field of the contract schema is required. The required job
 * roles are the one exception: they belong to /v1/shiftrequiredjobroles, so leaving the list out
 * keeps the roles the shift already has.
 */
public record UpdateShiftRequest(
        @Positive long departmentId,
        @Positive long workLocationId,
        @NotBlank String shiftName,
        @NotNull Instant startDatetime,
        @NotNull Instant endDatetime,
        @NotBlank @Pattern(regexp = "PLANNED|PUBLISHED|CANCELLED|LOCKED",
                message = "must be PLANNED, PUBLISHED, CANCELLED or LOCKED") String shiftStatus,
        @Valid List<RequiredJobRoleRequest> requiredJobRoles) {

    public NewShift toNewShift() {
        return new NewShift(departmentId, workLocationId, shiftName, startDatetime, endDatetime,
                ShiftStatus.valueOf(shiftStatus),
                requiredJobRoles == null
                        ? null
                        : requiredJobRoles.stream()
                                .map(role -> new RequiredJobRole(role.jobRoleId(), role.requiredEmployeeCount()))
                                .toList());
    }
}
