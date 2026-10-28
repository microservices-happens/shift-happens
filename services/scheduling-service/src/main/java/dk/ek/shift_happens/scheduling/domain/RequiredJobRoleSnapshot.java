package dk.ek.shift_happens.scheduling.domain;

/** One entry of requiredJobRoles inside the shift snapshot (contracts/events/payloads/shift.schema.json). */
public record RequiredJobRoleSnapshot(long jobRoleId, int requiredEmployeeCount) {
}
