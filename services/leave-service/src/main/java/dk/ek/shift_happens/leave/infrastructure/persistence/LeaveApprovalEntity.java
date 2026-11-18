package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.ApprovalDecision;
import dk.ek.shift_happens.leave.domain.LeaveApproval;
import dk.ek.shift_happens.leave.domain.LeaveDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for leave_approval (V3__leave_domain.sql). */
@Entity
@Table(name = "leave_approval")
public class LeaveApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "leave_approval_id")
    private Long leaveApprovalId;

    @Column(name = "leave_request_id", nullable = false)
    private long leaveRequestId;

    @Column(name = "approver_employee_id", nullable = false)
    private long approverEmployeeId;

    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "leave_comment", columnDefinition = "text")
    private String leaveComment;

    @Column(name = "decision_datetime", nullable = false)
    private Instant decisionDatetime;

    protected LeaveApprovalEntity() {
        // for JPA
    }

    static LeaveApprovalEntity from(LeaveApproval approval) {
        LeaveApprovalEntity entity = new LeaveApprovalEntity();
        entity.leaveApprovalId = approval.leaveApprovalId();
        entity.leaveRequestId = approval.leaveRequestId();
        entity.approverEmployeeId = approval.approverEmployeeId();
        entity.decision = approval.decision().name();
        entity.leaveComment = approval.leaveComment();
        entity.decisionDatetime = approval.decisionDatetime();
        return entity;
    }

    LeaveApproval toDomain() {
        return new LeaveApproval(leaveApprovalId, leaveRequestId, approverEmployeeId,
                ApprovalDecision.valueOf(decision), leaveComment, decisionDatetime);
    }

    LeaveDecision toLeaveDecision() {
        return toDomain().toDecision();
    }
}
