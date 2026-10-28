package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.List;

/**
 * data of scheduling.shift.created.v1 / .updated.v1, matching
 * contracts/events/payloads/shift.schema.json field for field (snapshot pattern: the full state
 * after the change, so a consumer never has to call back).
 */
public record ShiftSnapshot(
        long shiftId,
        long departmentId,
        long workLocationId,
        String shiftName,
        Instant startDatetime,
        Instant endDatetime,
        String shiftStatus,
        List<RequiredJobRoleSnapshot> requiredJobRoles) {
}
