package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.domain.ApprovalDecision;
import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignment;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignmentRepository;
import dk.ek.shift_happens.scheduling.domain.ShiftRepository;
import dk.ek.shift_happens.scheduling.domain.ShiftSwap;
import dk.ek.shift_happens.scheduling.domain.ShiftSwapApproval;
import dk.ek.shift_happens.scheduling.domain.ShiftSwapApprovalRepository;
import dk.ek.shift_happens.scheduling.domain.ShiftSwapRepository;
import dk.ek.shift_happens.scheduling.domain.SwapDecision;
import dk.ek.shift_happens.scheduling.domain.SwapRejectedException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Shift swaps (P2.9): an employee hands an assignment to a colleague, a manager decides, and an
 * approval reassigns in the same transaction.
 *
 * <p>The colleague is checked with the same {@link AssignmentPolicy} that guards a new assignment, so a
 * swap can never produce an assignment that a direct assignment would have refused.
 *
 * <p>A decision can be corrected but not reversed: the catalog defines no swap reversal event, so a
 * published decision stays the state of the swap and only its approver and comment can be fixed.
 */
@Service
@Profile("command")
public class ShiftSwapService {

    private final ShiftRepository shifts;
    private final ShiftAssignmentRepository assignments;
    private final ShiftSwapRepository swaps;
    private final ShiftSwapApprovalRepository approvals;
    private final AssignmentPolicy policy;
    private final DomainEvents events;

    ShiftSwapService(ShiftRepository shifts, ShiftAssignmentRepository assignments, ShiftSwapRepository swaps,
                     ShiftSwapApprovalRepository approvals, AssignmentPolicy policy, DomainEvents events) {
        this.shifts = shifts;
        this.assignments = assignments;
        this.swaps = swaps;
        this.approvals = approvals;
        this.policy = policy;
        this.events = events;
    }

    @Transactional
    public ShiftSwap request(long shiftAssignmentId, long employeeFromId, long employeeToId, String reason,
                             String correlationId) {
        ShiftAssignment assignment = activeAssignment(shiftAssignmentId);
        Shift shift = shiftOf(assignment);

        if (swaps.findPendingForAssignment(shiftAssignmentId).isPresent()) {
            throw new SwapRejectedException("assignment " + shiftAssignmentId + " already has a pending swap");
        }
        policy.requireAssignable(shift, employeeToId);

        ShiftSwap saved = swaps.save(ShiftSwap.request(assignment, employeeFromId, employeeToId, reason,
                Instant.now()));
        events.append(DomainEvents.SWAP_REQUESTED, saved.shiftSwapId(), saved.aggregateVersion(), correlationId,
                saved.snapshot());
        return saved;
    }

    /** PUT /v1/shiftswaps/{id}: another colleague or reason, while the swap is still pending. */
    @Transactional
    public ShiftSwap replace(long shiftSwapId, long employeeToId, String reason, String correlationId) {
        ShiftSwap current = pendingSwap(shiftSwapId);
        policy.requireAssignable(shiftOf(activeAssignment(current.originalShiftAssignmentId())), employeeToId);

        ShiftSwap updated = swaps.save(current.reRequested(employeeToId, reason));
        events.append(DomainEvents.SWAP_REQUESTED, updated.shiftSwapId(), updated.aggregateVersion(),
                correlationId, updated.snapshot());
        return updated;
    }

    /**
     * DELETE /v1/shiftswaps/{id}: withdrawing a pending request. The catalog defines no
     * scheduling.swap.deleted.v1, so this is the one delete in the service that publishes nothing.
     */
    @Transactional
    public void withdraw(long shiftSwapId) {
        swaps.delete(pendingSwap(shiftSwapId));
    }

    /** POST /v1/shiftswapapprovals: one decision, and APPROVED moves the assignment in this transaction. */
    @Transactional
    public ShiftSwapApproval decide(long shiftSwapId, long approverEmployeeId, ApprovalDecision decision,
                                    String comment, String correlationId) {
        ShiftSwap current = pendingSwap(shiftSwapId);
        ShiftAssignment assignment = activeAssignment(current.originalShiftAssignmentId());
        Shift shift = shiftOf(assignment);

        // The world may have moved since the request, so the colleague is re-checked before the handover.
        if (decision == ApprovalDecision.APPROVED) {
            policy.requireAssignable(shift, current.employeeToId());
        }

        ShiftSwapApproval saved = approvals.save(new ShiftSwapApproval(null, shiftSwapId, approverEmployeeId,
                decision, comment, Instant.now()));
        ShiftSwap decided = swaps.save(current.decided(new SwapDecision(approverEmployeeId, decision, comment,
                saved.decisionDatetime())));

        if (decision == ApprovalDecision.APPROVED) {
            ShiftAssignment moved = assignments.save(assignment.swappedTo(decided.employeeToId()));
            events.append(DomainEvents.ASSIGNMENT_UPDATED, moved.shiftAssignmentId(),
                    moved.aggregateVersion(), correlationId, moved.toSnapshot(shift));
            events.append(DomainEvents.SWAP_APPROVED, decided.shiftSwapId(), decided.aggregateVersion(),
                    correlationId, decided.snapshot());
        } else {
            events.append(DomainEvents.SWAP_REJECTED, decided.shiftSwapId(), decided.aggregateVersion(),
                    correlationId, decided.snapshot());
        }
        return saved;
    }

    /**
     * PUT /v1/shiftswapapprovals/{id}: corrects who decided and the comment of an existing decision.
     *
     * <p>The decision itself is final (no reversal event exists), so a different APPROVED/REJECTED value
     * is a 409. A correction republishes the same event type with the next aggregateVersion, which is
     * what a commutative consumer expects: the highest version wins.
     */
    @Transactional
    public ShiftSwapApproval correctDecision(long shiftSwapApprovalId, long shiftSwapId,
                                             long approverEmployeeId, ApprovalDecision decision, String comment,
                                             String correlationId) {
        ShiftSwapApproval existing = approval(shiftSwapApprovalId);
        if (existing.shiftSwapId() != shiftSwapId) {
            throw new SwapRejectedException("approval " + shiftSwapApprovalId + " belongs to swap "
                    + existing.shiftSwapId() + ", not " + shiftSwapId);
        }

        ShiftSwap current = swaps.findById(existing.shiftSwapId())
                .orElseThrow(() -> new SwapNotFoundException(existing.shiftSwapId()));
        // The decision is stored in shift_swap_approval and joined in when the swap is read back, so the
        // approval row has to be written first or the published snapshot would still carry the old comment.
        ShiftSwapApproval saved = approvals.save(new ShiftSwapApproval(existing.shiftSwapApprovalId(),
                existing.shiftSwapId(), approverEmployeeId, decision, comment, existing.decisionDatetime()));
        ShiftSwap corrected = swaps.save(current.decisionCorrected(new SwapDecision(approverEmployeeId,
                decision, comment, existing.decisionDatetime())));

        events.append(
                decision == ApprovalDecision.APPROVED ? DomainEvents.SWAP_APPROVED : DomainEvents.SWAP_REJECTED,
                corrected.shiftSwapId(), corrected.aggregateVersion(), correlationId, corrected.snapshot());
        return saved;
    }

    /**
     * DELETE /v1/shiftswapapprovals/{id}: removes the record of a decision.
     *
     * <p>It does not undo the handover: the swap keeps the status it already published, and no event is
     * written because the catalog defines no reversal. The history stays in audit-service.
     */
    @Transactional
    public void retractDecision(long shiftSwapApprovalId) {
        approvals.delete(approval(shiftSwapApprovalId));
    }

    private ShiftSwapApproval approval(long shiftSwapApprovalId) {
        return approvals.findById(shiftSwapApprovalId)
                .orElseThrow(() -> new ApprovalNotFoundException(shiftSwapApprovalId));
    }


    private ShiftSwap pendingSwap(long shiftSwapId) {
        ShiftSwap swap = swaps.findById(shiftSwapId)
                .orElseThrow(() -> new SwapNotFoundException(shiftSwapId));
        if (!swap.status().isPending()) {
            throw new SwapRejectedException("swap " + shiftSwapId + " is already " + swap.status());
        }
        return swap;
    }

    private ShiftAssignment activeAssignment(long shiftAssignmentId) {
        return assignments.findById(shiftAssignmentId)
                .filter(assignment -> !assignment.deleted())
                .orElseThrow(() -> new AssignmentNotFoundException(shiftAssignmentId));
    }

    private Shift shiftOf(ShiftAssignment assignment) {
        return shifts.findById(assignment.shiftId())
                .filter(shift -> !shift.deleted())
                .orElseThrow(() -> new ShiftNotFoundException(assignment.shiftId()));
    }
}
