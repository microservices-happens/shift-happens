package dk.ek.shift_happens.leave.domain;

import java.time.Instant;
import java.util.Optional;

/**
 * Leave's local copy of an employee, built from workforce.employee.* events (replica pattern,
 * contracts/README.md). It exists so validating a leave request never needs a synchronous call.
 *
 * <p>{@code deletedAt} is the retained tombstone. The merge rules live here, so they are testable
 * without Spring: the highest aggregateVersion wins, a snapshot never clears a tombstone, and a
 * tombstone always wins, even when it arrives before the employee was ever created.
 */
public record EmployeeReplica(
        long employeeId,
        String firstName,
        String lastName,
        String employmentStatus,
        long aggregateVersion,
        Instant deletedAt) {

    public static final String ACTIVE = "ACTIVE";

    /** May request leave: not tombstoned and currently employed. */
    public boolean isActive() {
        return deletedAt == null && ACTIVE.equalsIgnoreCase(employmentStatus);
    }

    /**
     * Applies a created/updated snapshot. Empty means "ignore": the stored version is already the same
     * or newer. An existing tombstone is carried over, never cleared.
     */
    public static Optional<EmployeeReplica> applySnapshot(EmployeeReplica current, EmployeeSnapshot snapshot,
                                                          long version) {
        if (current != null && version <= current.aggregateVersion()) {
            return Optional.empty();
        }
        return Optional.of(new EmployeeReplica(
                snapshot.employeeId(),
                snapshot.firstName(),
                snapshot.lastName(),
                snapshot.employmentStatus(),
                version,
                current == null ? null : current.deletedAt()));
    }

    /**
     * Applies a tombstone. It always wins: with no row yet a tombstoned row is created, and a
     * tombstone with a lower version than the stored one still sets deletedAt (the higher version is
     * kept). Empty means nothing would change (already tombstoned at an equal or higher version).
     */
    public static Optional<EmployeeReplica> applyTombstone(EmployeeReplica current, long employeeId, long version,
                                                           Instant deletedAt) {
        if (current == null) {
            return Optional.of(new EmployeeReplica(employeeId, null, null, null, version, deletedAt));
        }
        Instant effectiveDeletedAt = current.deletedAt() != null ? current.deletedAt() : deletedAt;
        long effectiveVersion = Math.max(current.aggregateVersion(), version);
        if (current.deletedAt() != null && effectiveVersion == current.aggregateVersion()) {
            return Optional.empty();
        }
        return Optional.of(new EmployeeReplica(current.employeeId(), current.firstName(), current.lastName(),
                current.employmentStatus(), effectiveVersion, effectiveDeletedAt));
    }
}
