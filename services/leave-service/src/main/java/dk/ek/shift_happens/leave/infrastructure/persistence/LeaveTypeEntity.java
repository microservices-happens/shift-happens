package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.LeaveType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for leave_type (V3__leave_domain.sql, V6__leave_type_events.sql). */
@Entity
@Table(name = "leave_type")
public class LeaveTypeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "leave_type_id")
    private Long leaveTypeId;

    @Column(name = "leave_type_name", nullable = false, length = 100)
    private String leaveTypeName;

    @Column(name = "leave_type_description", columnDefinition = "text")
    private String leaveTypeDescription;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval;

    @Column(name = "is_paid_leave", nullable = false)
    private boolean paidLeave;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected LeaveTypeEntity() {
        // for JPA
    }

    static LeaveTypeEntity from(LeaveType type) {
        LeaveTypeEntity entity = new LeaveTypeEntity();
        entity.leaveTypeId = type.leaveTypeId();
        entity.leaveTypeName = type.leaveTypeName();
        entity.leaveTypeDescription = type.leaveTypeDescription();
        entity.requiresApproval = type.requiresApproval();
        entity.paidLeave = type.paidLeave();
        entity.aggregateVersion = type.aggregateVersion();
        entity.deletedAt = type.deletedAt();
        return entity;
    }

    LeaveType toDomain() {
        return new LeaveType(leaveTypeId, leaveTypeName, leaveTypeDescription, requiresApproval, paidLeave,
                aggregateVersion, deletedAt);
    }
}
