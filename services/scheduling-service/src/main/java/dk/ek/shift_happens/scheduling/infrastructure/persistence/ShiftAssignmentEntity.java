package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.AssignmentStatus;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for shift_assignment (V3__assignments.sql). */
@Entity
@Table(name = "shift_assignment")
public class ShiftAssignmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shift_assignment_id")
    private Long shiftAssignmentId;

    @Column(name = "shift_id", nullable = false)
    private long shiftId;

    @Column(name = "employee_id", nullable = false)
    private long employeeId;

    @Column(name = "assignment_status", nullable = false, length = 30)
    private String assignmentStatus;

    @Column(name = "assigned_datetime")
    private Instant assignedDatetime;

    @Column(name = "check_in_datetime")
    private Instant checkInDatetime;

    @Column(name = "check_out_datetime")
    private Instant checkOutDatetime;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    protected ShiftAssignmentEntity() {
        // for JPA
    }

    static ShiftAssignmentEntity from(ShiftAssignment assignment) {
        ShiftAssignmentEntity entity = new ShiftAssignmentEntity();
        entity.shiftAssignmentId = assignment.shiftAssignmentId();
        entity.shiftId = assignment.shiftId();
        entity.employeeId = assignment.employeeId();
        entity.assignmentStatus = assignment.status().name();
        entity.assignedDatetime = assignment.assignedDatetime();
        entity.checkInDatetime = assignment.checkInDatetime();
        entity.checkOutDatetime = assignment.checkOutDatetime();
        entity.aggregateVersion = assignment.aggregateVersion();
        entity.deleted = assignment.deleted();
        return entity;
    }

    ShiftAssignment toDomain() {
        return new ShiftAssignment(shiftAssignmentId, shiftId, employeeId,
                AssignmentStatus.valueOf(assignmentStatus), assignedDatetime, checkInDatetime,
                checkOutDatetime, aggregateVersion, deleted);
    }
}
