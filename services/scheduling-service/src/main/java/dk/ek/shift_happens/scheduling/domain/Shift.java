package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.List;

/**
 * A shift as Scheduling owns it. Plain Java: no Spring, no JPA, no clock of its own, so every rule
 * is unit-testable without a context (clinerules/20-java-conventions.md).
 */
public record Shift(
        Long shiftId,
        long departmentId,
        long workLocationId,
        String shiftName,
        Instant startDatetime,
        Instant endDatetime,
        ShiftStatus status,
        List<RequiredJobRole> requiredJobRoles,
        long aggregateVersion) {

    /**
     * Creates a new shift: the id and the first aggregate version are assigned on save.
     *
     * <p>The rules are the ones the contract implies: a positive duration, a name, one row per job
     * role, and a new shift is either PLANNED or PUBLISHED. CANCELLED and LOCKED are reached later
     * through an update, never on create.
     */
    public static Shift create(NewShift newShift) {
        if (newShift.shiftName() == null || newShift.shiftName().isBlank()) {
            throw new IllegalArgumentException("shiftName is required");
        }
        if (newShift.departmentId() <= 0) {
            throw new IllegalArgumentException("departmentId must be positive");
        }
        if (newShift.workLocationId() <= 0) {
            throw new IllegalArgumentException("workLocationId must be positive");
        }
        if (newShift.startDatetime() == null || newShift.endDatetime() == null) {
            throw new IllegalArgumentException("startDatetime and endDatetime are required");
        }
        if (!newShift.endDatetime().isAfter(newShift.startDatetime())) {
            throw new IllegalArgumentException("endDatetime must be after startDatetime");
        }

        List<RequiredJobRole> requiredJobRoles =
                newShift.requiredJobRoles() == null ? List.of() : List.copyOf(newShift.requiredJobRoles());
        long distinctRoles = requiredJobRoles.stream().map(RequiredJobRole::jobRoleId).distinct().count();
        if (distinctRoles != requiredJobRoles.size()) {
            throw new IllegalArgumentException("each jobRoleId may appear only once per shift");
        }

        ShiftStatus status = newShift.status() == null ? ShiftStatus.PLANNED : newShift.status();
        if (status.isCancelled() || status == ShiftStatus.LOCKED) {
            throw new IllegalArgumentException("a new shift must be PLANNED or PUBLISHED");
        }

        return new Shift(null, newShift.departmentId(), newShift.workLocationId(), newShift.shiftName(),
                newShift.startDatetime(), newShift.endDatetime(), status, requiredJobRoles, 1);
    }

    /** The full snapshot published as scheduling.shift.created.v1 / .updated.v1. */
    public ShiftSnapshot snapshot() {
        if (shiftId == null) {
            throw new IllegalStateException("a shift must be saved before its snapshot can be published");
        }
        return new ShiftSnapshot(shiftId, departmentId, workLocationId, shiftName, startDatetime,
                endDatetime, status.name(), requiredJobRoles);
    }
}
