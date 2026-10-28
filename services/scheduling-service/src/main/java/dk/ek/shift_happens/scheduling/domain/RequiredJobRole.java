package dk.ek.shift_happens.scheduling.domain;

/**
 * How many employees a shift needs for one job role (shift_required_job_role).
 *
 * <p>{@code shiftRequiredJobRoleId} is null until the row is stored: the contract addresses a row by
 * that id (PUT/DELETE /v1/shiftrequiredjobroles/{id}) and returns it, so it belongs to the model.
 * jobRoleId is a Workforce id: Scheduling checks it against its local employee replica (P2.8) and it
 * is never a foreign key across services.
 */
public record RequiredJobRole(Long shiftRequiredJobRoleId, long jobRoleId, int requiredEmployeeCount) {

    public RequiredJobRole {
        if (jobRoleId <= 0) {
            throw new IllegalArgumentException("jobRoleId must be positive");
        }
        if (requiredEmployeeCount < 1) {
            throw new IllegalArgumentException("requiredEmployeeCount must be at least 1");
        }
    }

    /** A row that has not been stored yet. */
    public RequiredJobRole(long jobRoleId, int requiredEmployeeCount) {
        this(null, jobRoleId, requiredEmployeeCount);
    }
}
