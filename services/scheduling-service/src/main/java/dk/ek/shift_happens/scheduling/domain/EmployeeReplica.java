package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Scheduling's local copy of an employee, built from workforce.employee.* events (replica pattern,
 * contracts/README.md). It exists so a qualification check never needs a synchronous call.
 *
 * <p>{@code deletedAt} is the retained tombstone: the row stays so a late, lower-version update can
 * never make a deleted employee assignable again. {@code weeklyContractHours} comes from the same
 * snapshot and is what the understaffed candidates report.
 *
 * <p>The merge rules live here, so they are testable without Spring, and they are written so the row
 * does not depend on the order the events arrive in (commutative handler, P3.3):
 * {@code snapshotVersion} is the version of the snapshot the employee fields come from (0 while only a
 * tombstone has arrived), {@code aggregateVersion} the highest version seen, snapshot or tombstone.
 */
public record EmployeeReplica(
        long employeeId,
        String firstName,
        String lastName,
        String employmentStatus,
        List<Long> jobRoleIds,
        Long primaryWorkLocationId,
        long aggregateVersion,
        Instant deletedAt,
        double weeklyContractHours,
        long snapshotVersion) {

    public static final String ACTIVE = "ACTIVE";

    /** A row whose employee fields are as new as its version: the normal case. */
    public EmployeeReplica(long employeeId, String firstName, String lastName, String employmentStatus,
                           List<Long> jobRoleIds, Long primaryWorkLocationId, long aggregateVersion,
                           Instant deletedAt, double weeklyContractHours) {
        this(employeeId, firstName, lastName, employmentStatus, jobRoleIds, primaryWorkLocationId,
                aggregateVersion, deletedAt, weeklyContractHours, aggregateVersion);
    }

    /**
     * Applies a created/updated snapshot. Empty means "ignore".
     *
     * <p>The employee fields are replaced only by a newer snapshot than the one they come from. On a
     * tombstoned row that still holds for a snapshot older than the tombstone (it was published before
     * the delete and overtaken by it), so the row ends with the last snapshot before the delete in any
     * order. A snapshot that is not older than the tombstone is ignored, and {@code deletedAt} is
     * always carried over: a snapshot never clears a tombstone.
     */
    public static Optional<EmployeeReplica> applySnapshot(EmployeeReplica current, EmployeeSnapshot snapshot,
                                                          long version) {
        List<Long> jobRoleIds = snapshot.jobRoleIds() == null ? List.of() : snapshot.jobRoleIds();
        double weeklyContractHours = snapshot.weeklyContractHours() == null ? 0 : snapshot.weeklyContractHours();
        if (current == null) {
            return Optional.of(new EmployeeReplica(snapshot.employeeId(), snapshot.firstName(),
                    snapshot.lastName(), snapshot.employmentStatus(), jobRoleIds,
                    snapshot.primaryWorkLocationId(), version, null, weeklyContractHours, version));
        }
        if (version <= current.snapshotVersion()) {
            return Optional.empty();
        }
        if (current.deletedAt() != null && version >= current.aggregateVersion()) {
            return Optional.empty();
        }
        return Optional.of(new EmployeeReplica(current.employeeId(), snapshot.firstName(), snapshot.lastName(),
                snapshot.employmentStatus(), jobRoleIds, snapshot.primaryWorkLocationId(),
                Math.max(current.aggregateVersion(), version), current.deletedAt(), weeklyContractHours,
                version));
    }

    /**
     * Applies a tombstone. Empty means nothing would change.
     *
     * <p>With no row yet (the delete overtook the create) a tombstoned row without employee fields is
     * created, so the late create cannot make the employee assignable. On an existing row the
     * tombstone is set and the highest version is kept.
     */
    public static Optional<EmployeeReplica> applyTombstone(EmployeeReplica current, long employeeId, long version,
                                                           Instant deletedAt) {
        if (current == null) {
            return Optional.of(new EmployeeReplica(employeeId, null, null, null, List.of(), null, version,
                    deletedAt, 0, 0));
        }
        if (current.deletedAt() != null && version <= current.aggregateVersion()) {
            return Optional.empty();
        }
        return Optional.of(new EmployeeReplica(current.employeeId(), current.firstName(), current.lastName(),
                current.employmentStatus(), current.jobRoleIds(), current.primaryWorkLocationId(),
                Math.max(current.aggregateVersion(), version),
                current.deletedAt() != null ? current.deletedAt() : deletedAt,
                current.weeklyContractHours(), current.snapshotVersion()));
    }

    /** Assignable: not tombstoned and currently employed. */
    public boolean isActive() {
        return deletedAt == null && ACTIVE.equalsIgnoreCase(employmentStatus);
    }

    public boolean hasJobRole(long jobRoleId) {
        return jobRoleIds != null && jobRoleIds.contains(jobRoleId);
    }

    public String fullName() {
        return (firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName);
    }
}
