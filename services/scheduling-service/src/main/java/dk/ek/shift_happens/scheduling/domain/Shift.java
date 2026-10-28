package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.List;

/**
 * A shift as Scheduling owns it. Plain Java: no Spring, no JPA, no clock of its own, so every rule
 * is unit-testable without a context (clinerules/20-java-conventions.md).
 *
 * <p>{@code deleted} is the soft-delete flag: the row stays and scheduling.shift.deleted.v1 carries
 * a tombstone, so a late update can never resurrect the shift (contracts/README.md, "Tombstones").
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
        long aggregateVersion,
        boolean deleted) {

    /**
     * Creates a new shift: the id and the first aggregate version are assigned on save.
     *
     * <p>A new shift is either PLANNED or PUBLISHED; CANCELLED and LOCKED are reached by an update.
     */
    public static Shift create(NewShift newShift) {
        validate(newShift);
        ShiftStatus status = newShift.status() == null ? ShiftStatus.PLANNED : newShift.status();
        if (status.isCancelled() || status == ShiftStatus.LOCKED) {
            throw new IllegalArgumentException("a new shift must be PLANNED or PUBLISHED");
        }
        return new Shift(null, newShift.departmentId(), newShift.workLocationId(), newShift.shiftName(),
                newShift.startDatetime(), newShift.endDatetime(), status, requiredJobRolesOf(newShift), 1, false);
    }

    /**
     * Full replacement of the mutable fields (PUT /v1/shifts/{id}). The status may only move forward,
     * and requiredJobRoles may be omitted, in which case the shift keeps the ones it has.
     */
    public Shift updated(NewShift changes) {
        if (deleted) {
            throw new IllegalStateException("a deleted shift cannot be changed");
        }
        validate(changes);
        ShiftStatus target = changes.status() == null ? status : changes.status();
        if (!status.canTransitionTo(target)) {
            throw new ShiftNotEditableException(shiftId, status, target);
        }
        List<RequiredJobRole> roles = changes.requiredJobRoles() == null
                ? requiredJobRoles
                : requiredJobRolesOf(changes);
        return new Shift(shiftId, changes.departmentId(), changes.workLocationId(), changes.shiftName(),
                changes.startDatetime(), changes.endDatetime(), target, roles, aggregateVersion + 1, false);
    }

    /** Soft delete (DELETE /v1/shifts/{id}): the row stays, the version moves on, the event is a tombstone. */
    public Shift markedDeleted() {
        if (deleted) {
            throw new IllegalStateException("the shift is already deleted");
        }
        return new Shift(shiftId, departmentId, workLocationId, shiftName, startDatetime, endDatetime,
                status, requiredJobRoles, aggregateVersion + 1, true);
    }

    /** The full snapshot published as scheduling.shift.created.v1 / .updated.v1. */
    public ShiftSnapshot snapshot() {
        if (shiftId == null) {
            throw new IllegalStateException("a shift must be saved before its snapshot can be published");
        }
        return new ShiftSnapshot(shiftId, departmentId, workLocationId, shiftName, startDatetime,
                endDatetime, status.name(), requiredJobRoles);
    }

    private static void validate(NewShift newShift) {
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

        List<RequiredJobRole> requiredJobRoles = requiredJobRolesOf(newShift);
        long distinctRoles = requiredJobRoles.stream().map(RequiredJobRole::jobRoleId).distinct().count();
        if (distinctRoles != requiredJobRoles.size()) {
            throw new IllegalArgumentException("each jobRoleId may appear only once per shift");
        }
    }

    private static List<RequiredJobRole> requiredJobRolesOf(NewShift newShift) {
        return newShift.requiredJobRoles() == null ? List.of() : List.copyOf(newShift.requiredJobRoles());
    }
}
