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
