package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.List;

/** Input of the create-shift use case, before the invariants of {@link Shift} are applied. */
public record NewShift(
        long departmentId,
        long workLocationId,
        String shiftName,
        Instant startDatetime,
        Instant endDatetime,
        ShiftStatus status,
        List<RequiredJobRole> requiredJobRoles) {
}
