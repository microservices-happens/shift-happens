package dk.ek.shift_happens.scheduling.domain;

/**
 * How many employees a shift needs for one job role (shift_required_job_role).
 *
 * <p>jobRoleId is a Workforce id: Scheduling validates it against its local employee replica once
 * that replica exists (P2.8). It is not a foreign key, because the two services own their own data.
 */
public record RequiredJobRole(long jobRoleId, int requiredEmployeeCount) {

    public RequiredJobRole {
        if (jobRoleId <= 0) {
            throw new IllegalArgumentException("jobRoleId must be positive");
        }
        if (requiredEmployeeCount < 1) {
            throw new IllegalArgumentException("requiredEmployeeCount must be at least 1");
        }
    }
}
