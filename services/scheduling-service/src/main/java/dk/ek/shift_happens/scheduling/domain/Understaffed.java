package dk.ek.shift_happens.scheduling.domain;

/**
 * data of scheduling.shift.understaffed.v1, matching
 * contracts/events/payloads/understaffed.schema.json field for field. aggregateId = shiftId.
 */
public record Understaffed(
        long shiftId,
        String shiftName,
        long departmentId,
        java.time.Instant startDatetime,
        java.time.Instant endDatetime,
        long jobRoleId,
        int missingCount,
        java.util.List<Candidate> candidates) {
}
