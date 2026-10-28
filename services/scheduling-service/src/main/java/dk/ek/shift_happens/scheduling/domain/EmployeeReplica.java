package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.List;

/**
 * Scheduling's local copy of an employee, built from workforce.employee.* events (replica pattern,
 * contracts/README.md). It exists so a qualification check never needs a synchronous call.
 *
 * <p>{@code deletedAt} is the retained tombstone: the row stays so a late, lower-version update can
 * never make a deleted employee assignable again.
 */
public record EmployeeReplica(
        long employeeId,
        String firstName,
        String lastName,
        String employmentStatus,
        List<Long> jobRoleIds,
        Long primaryWorkLocationId,
        long aggregateVersion,
        Instant deletedAt) {

    public static final String ACTIVE = "ACTIVE";

    /** Assignable: not tombstoned and currently employed. */
    public boolean isActive() {
        return deletedAt == null && ACTIVE.equalsIgnoreCase(employmentStatus);
    }

    public boolean hasJobRole(long jobRoleId) {
        return jobRoleIds != null && jobRoleIds.contains(jobRoleId);
    }

    /** Commutative handler: keep the snapshot with the highest aggregateVersion. */
    public boolean accepts(long version) {
        return version > aggregateVersion;
    }

    public String fullName() {
        return (firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName);
    }
}
