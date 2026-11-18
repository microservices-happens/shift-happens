package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.EmployeeReplica;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for employee_replica (V2). The id comes from Workforce. */
@Entity
@Table(name = "employee_replica")
public class EmployeeReplicaEntity {

    @Id
    @Column(name = "employee_id")
    private long employeeId;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Column(name = "employment_status", length = 50)
    private String employmentStatus;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected EmployeeReplicaEntity() {
        // for JPA
    }

    static EmployeeReplicaEntity from(EmployeeReplica employee) {
        EmployeeReplicaEntity entity = new EmployeeReplicaEntity();
        entity.employeeId = employee.employeeId();
        entity.firstName = employee.firstName();
        entity.lastName = employee.lastName();
        entity.employmentStatus = employee.employmentStatus();
        entity.aggregateVersion = employee.aggregateVersion();
        entity.deletedAt = employee.deletedAt();
        return entity;
    }

    EmployeeReplica toDomain() {
        return new EmployeeReplica(employeeId, firstName, lastName, employmentStatus, aggregateVersion, deletedAt);
    }
}
