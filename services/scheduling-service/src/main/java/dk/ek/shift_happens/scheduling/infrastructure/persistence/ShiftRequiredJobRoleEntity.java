package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.RequiredJobRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row of shift_required_job_role. It is a real entity (not an embedded collection) because the
 * contract addresses a row by shiftRequiredJobRoleId and returns that id.
 *
 * <p>The owning shift_id column is managed by the association on {@link ShiftEntity}.
 */
@Entity
@Table(name = "shift_required_job_role")
public class ShiftRequiredJobRoleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shift_required_job_role_id")
    private Long shiftRequiredJobRoleId;

    @Column(name = "job_role_id", nullable = false)
    private long jobRoleId;

    @Column(name = "required_employee_count", nullable = false)
    private int requiredEmployeeCount;

    protected ShiftRequiredJobRoleEntity() {
        // for JPA
    }

    private ShiftRequiredJobRoleEntity(Long shiftRequiredJobRoleId, long jobRoleId, int requiredEmployeeCount) {
        this.shiftRequiredJobRoleId = shiftRequiredJobRoleId;
        this.jobRoleId = jobRoleId;
        this.requiredEmployeeCount = requiredEmployeeCount;
    }

    static ShiftRequiredJobRoleEntity from(RequiredJobRole requiredJobRole) {
        return new ShiftRequiredJobRoleEntity(requiredJobRole.shiftRequiredJobRoleId(),
                requiredJobRole.jobRoleId(), requiredJobRole.requiredEmployeeCount());
    }

    RequiredJobRole toDomain() {
        return new RequiredJobRole(shiftRequiredJobRoleId, jobRoleId, requiredEmployeeCount);
    }
}
