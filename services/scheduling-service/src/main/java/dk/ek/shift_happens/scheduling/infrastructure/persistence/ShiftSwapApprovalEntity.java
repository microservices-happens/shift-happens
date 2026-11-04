package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.ApprovalDecision;
import dk.ek.shift_happens.scheduling.domain.ShiftSwapApproval;
import dk.ek.shift_happens.scheduling.domain.SwapDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for shift_swap_approval (V5__swaps.sql). */
@Entity
@Table(name = "shift_swap_approval")
public class ShiftSwapApprovalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "shift_swap_approval_id")
    private Long shiftSwapApprovalId;

    @Column(name = "shift_swap_id", nullable = false)
    private long shiftSwapId;

    @Column(name = "approver_employee_id", nullable = false)
    private long approverEmployeeId;

    @Column(name = "decision", nullable = false, length = 20)
    private String decision;

    @Column(name = "shift_swap_comment", columnDefinition = "text")
    private String comment;

    @Column(name = "decision_datetime")
    private Instant decisionDatetime;

    protected ShiftSwapApprovalEntity() {
        // for JPA
    }

    static ShiftSwapApprovalEntity from(ShiftSwapApproval approval) {
        ShiftSwapApprovalEntity entity = new ShiftSwapApprovalEntity();
        entity.shiftSwapApprovalId = approval.shiftSwapApprovalId();
        entity.shiftSwapId = approval.shiftSwapId();
        entity.approverEmployeeId = approval.approverEmployeeId();
        entity.decision = approval.decision().name();
        entity.comment = approval.comment();
        entity.decisionDatetime = approval.decisionDatetime();
        return entity;
    }

    ShiftSwapApproval toDomain() {
        return new ShiftSwapApproval(shiftSwapApprovalId, shiftSwapId, approverEmployeeId,
                ApprovalDecision.valueOf(decision), comment, decisionDatetime);
    }

    SwapDecision toSwapDecision() {
        return new SwapDecision(approverEmployeeId, ApprovalDecision.valueOf(decision), comment,
                decisionDatetime);
    }
}
