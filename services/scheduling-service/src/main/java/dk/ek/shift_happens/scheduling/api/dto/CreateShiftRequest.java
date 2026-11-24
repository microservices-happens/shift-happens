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
 * Request body of POST /v1/shifts (contracts/openapi/scheduling.yaml, schema Shift).
 *
 * <p>{@code shiftStatus} is required by the contract; a new shift may only be PLANNED or PUBLISHED
 * (the domain rule rejects CANCELLED and LOCKED). {@code requiredJobRoles} is optional: a manager may
 * create the shift first and add its staffing plan through /v1/shiftrequiredjobroles, or send both in
 * one request.
 */
public record CreateShiftRequest(
        @Positive long departmentId,
        @Positive long workLocationId,
        @NotBlank String shiftName,
        @NotNull Instant startDatetime,
        @NotNull Instant endDatetime,
        @NotBlank @Pattern(regexp = "PLANNED|PUBLISHED|CANCELLED|LOCKED",
                message = "must be PLANNED, PUBLISHED, CANCELLED or LOCKED") String shiftStatus,
        @Valid List<RequiredJobRoleRequest> requiredJobRoles) {

    public NewShift toNewShift() {
        List<RequiredJobRole> roles = requiredJobRoles == null
                ? List.of()
                : requiredJobRoles.stream()
                        .map(role -> new RequiredJobRole(role.jobRoleId(), role.requiredEmployeeCount()))
                        .toList();
        return new NewShift(departmentId, workLocationId, shiftName, startDatetime, endDatetime,
                ShiftStatus.valueOf(shiftStatus), roles);
    }
}
