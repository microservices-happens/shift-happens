package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.RequiredJobRole;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/** One row of shift_required_job_role, embedded in {@link ShiftEntity}. */
@Embeddable
public class RequiredJobRoleEmbeddable {

    @Column(name = "job_role_id", nullable = false)
    private long jobRoleId;

    @Column(name = "required_employee_count", nullable = false)
    private int requiredEmployeeCount;

    protected RequiredJobRoleEmbeddable() {
        // for JPA
    }

    private RequiredJobRoleEmbeddable(long jobRoleId, int requiredEmployeeCount) {
        this.jobRoleId = jobRoleId;
        this.requiredEmployeeCount = requiredEmployeeCount;
    }

    static RequiredJobRoleEmbeddable from(RequiredJobRole requiredJobRole) {
        return new RequiredJobRoleEmbeddable(requiredJobRole.jobRoleId(), requiredJobRole.requiredEmployeeCount());
    }

    RequiredJobRole toDomain() {
        return new RequiredJobRole(jobRoleId, requiredEmployeeCount);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RequiredJobRoleEmbeddable that)) {
            return false;
        }
        return jobRoleId == that.jobRoleId && requiredEmployeeCount == that.requiredEmployeeCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobRoleId, requiredEmployeeCount);
    }
}
