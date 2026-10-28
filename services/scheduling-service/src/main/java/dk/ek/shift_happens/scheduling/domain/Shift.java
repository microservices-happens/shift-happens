package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A shift as Scheduling owns it, together with the job roles it requires. Plain Java: no Spring, no
 * JPA, no clock of its own, so every rule is unit-testable without a context.
 *
 * <p>{@code deleted} is the soft-delete flag: the row stays and scheduling.shift.deleted.v1 carries a
 * tombstone, so a late update can never resurrect the shift (contracts/README.md, "Tombstones").
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
        requireNotDeleted();
        validate(changes);
        ShiftStatus target = changes.status() == null ? status : changes.status();
        if (!status.canTransitionTo(target)) {
            // Reported as a transition, which is the more precise of the two reasons.
            throw new ShiftNotEditableException(shiftId, status, target);
        }
        requireNotCancelled();
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

    /** POST /v1/shiftrequiredjobroles: one row per job role, and a cancelled shift takes no new staff. */
    public Shift withRequiredJobRoleAdded(RequiredJobRole role) {
        requireEditable();
        if (hasJobRole(role.jobRoleId(), null)) {
            throw new DuplicateRequiredJobRoleException(shiftId, role.jobRoleId());
        }
        List<RequiredJobRole> roles = new ArrayList<>(requiredJobRoles);
        roles.add(role);
        return withRequiredJobRoles(roles);
    }

    /** PUT /v1/shiftrequiredjobroles/{id}. */
    public Shift withRequiredJobRoleReplaced(long requiredJobRoleId, RequiredJobRole replacement) {
        requireEditable();
        int index = indexOfRequiredJobRole(requiredJobRoleId);
        if (hasJobRole(replacement.jobRoleId(), requiredJobRoleId)) {
            throw new DuplicateRequiredJobRoleException(shiftId, replacement.jobRoleId());
        }
        List<RequiredJobRole> roles = new ArrayList<>(requiredJobRoles);
        roles.set(index, new RequiredJobRole(requiredJobRoleId, replacement.jobRoleId(),
                replacement.requiredEmployeeCount()));
        return withRequiredJobRoles(roles);
    }

    /** DELETE /v1/shiftrequiredjobroles/{id}. */
    public Shift withoutRequiredJobRole(long requiredJobRoleId) {
        requireEditable();
        List<RequiredJobRole> roles = new ArrayList<>(requiredJobRoles);
        roles.remove(indexOfRequiredJobRole(requiredJobRoleId));
        return withRequiredJobRoles(roles);
    }

    /** How many employees the shift needs for a job role, 0 when it does not require it. */
    public int requiredCount(long jobRoleId) {
        return requiredJobRoles.stream()
                .filter(role -> role.jobRoleId() == jobRoleId)
                .mapToInt(RequiredJobRole::requiredEmployeeCount)
                .findFirst()
                .orElse(0);
    }

    /**
     * True when the shift declares job roles and the employee holds at least one of them. A shift
     * without required roles has nothing to check (P2.8).
     */
    public boolean accepts(EmployeeReplica employee) {
        if (requiredJobRoles.isEmpty()) {
            return true;
        }
        return requiredJobRoles.stream().anyMatch(role -> employee.hasJobRole(role.jobRoleId()));
    }

    /** Half-open interval overlap between this shift and another one: [start, end). */
    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        return startDatetime.isBefore(otherEnd) && otherStart.isBefore(endDatetime);
    }

    /** The full snapshot published as scheduling.shift.created.v1 / .updated.v1. */
    public ShiftSnapshot snapshot() {
        if (shiftId == null) {
            throw new IllegalStateException("a shift must be saved before its snapshot can be published");
        }
        return new ShiftSnapshot(shiftId, departmentId, workLocationId, shiftName, startDatetime,
                endDatetime, status.name(), requiredJobRoles.stream()
                        .map(role -> new RequiredJobRoleSnapshot(role.jobRoleId(), role.requiredEmployeeCount()))
                        .toList());
    }

    private Shift withRequiredJobRoles(List<RequiredJobRole> roles) {
        return new Shift(shiftId, departmentId, workLocationId, shiftName, startDatetime, endDatetime,
                status, List.copyOf(roles), aggregateVersion + 1, deleted);
    }

    private boolean hasJobRole(long jobRoleId, Long exceptRequiredJobRoleId) {
        return requiredJobRoles.stream()
                .anyMatch(role -> role.jobRoleId() == jobRoleId
                        && !Objects.equals(role.shiftRequiredJobRoleId(), exceptRequiredJobRoleId));
    }

    private int indexOfRequiredJobRole(long requiredJobRoleId) {
        for (int index = 0; index < requiredJobRoles.size(); index++) {
            Long existing = requiredJobRoles.get(index).shiftRequiredJobRoleId();
            if (existing != null && existing == requiredJobRoleId) {
                return index;
            }
        }
        throw new RequiredJobRoleNotFoundException(shiftId, requiredJobRoleId);
    }

    private void requireEditable() {
        requireNotDeleted();
        requireNotCancelled();
    }

    private void requireNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("a deleted shift cannot be changed");
        }
    }

    private void requireNotCancelled() {
        if (status.isCancelled()) {
            throw new ShiftNotEditableException(shiftId, "the shift is cancelled");
        }
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
