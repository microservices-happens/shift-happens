package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.ApprovalDecision;
import dk.ek.shift_happens.scheduling.domain.ShiftApproval;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for shift_approval (V3__assignments.sql). */
@Entity
@Table(name = "shift_approval")
public class ShiftApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shift_approval_id")
    private Long shiftApprovalId;

    @Column(name = "shift_assignment_id", nullable = false)
    private long shiftAssignmentId;

    @Column(name = "approver_employee_id", nullable = false)
    private long approverEmployeeId;

    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "approval_comment", columnDefinition = "text")
    private String approvalComment;

    @Column(name = "decision_datetime")
    private Instant decisionDatetime;

    protected ShiftApprovalEntity() {
        // for JPA
    }

    static ShiftApprovalEntity from(ShiftApproval approval) {
        ShiftApprovalEntity entity = new ShiftApprovalEntity();
        entity.shiftApprovalId = approval.shiftApprovalId();
        entity.shiftAssignmentId = approval.shiftAssignmentId();
        entity.approverEmployeeId = approval.approverEmployeeId();
        entity.decision = approval.decision().name();
        entity.approvalComment = approval.approvalComment();
        entity.decisionDatetime = approval.decisionDatetime();
        return entity;
    }

    ShiftApproval toDomain() {
        return new ShiftApproval(shiftApprovalId, shiftAssignmentId, approverEmployeeId,
                ApprovalDecision.valueOf(decision), approvalComment, decisionDatetime);
    }
}
