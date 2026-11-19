package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.LeaveType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** JPA mapping for leave_type (V3__leave_domain.sql). */
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
        return entity;
    }

    LeaveType toDomain() {
        return new LeaveType(leaveTypeId, leaveTypeName, leaveTypeDescription, requiresApproval, paidLeave);
    }
}
