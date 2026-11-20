package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.List;

/**
 * The local employee copy in the read model, built from workforce.employee.* (same replica idea as the
 * command role, but shaped for the schedule view). A tombstone is retained in {@code deletedAt}.
 */
public record EmployeeView(
        long employeeId,
        String firstName,
        String lastName,
        List<Long> jobRoleIds,
        String employmentStatus,
        double weeklyContractHours,
        long aggregateVersion,
        Instant deletedAt) {

    public boolean deleted() {
        return deletedAt != null;
    }

    public boolean hasJobRole(long jobRoleId) {
        return jobRoleIds != null && jobRoleIds.contains(jobRoleId);
    }

    public String fullName() {
        return (firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName);
    }
}
