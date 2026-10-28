package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.domain.ApprovalDecision;
import dk.ek.shift_happens.scheduling.domain.AssignmentRejectedException;
import dk.ek.shift_happens.scheduling.domain.AssignmentStatus;
import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftApproval;
import dk.ek.shift_happens.scheduling.domain.ShiftApprovalRepository;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignment;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignmentRepository;
import dk.ek.shift_happens.scheduling.domain.ShiftRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * A manager approves or rejects an assignment. One decision per assignment, and an approval moves the
 * assignment to APPROVED in the same transaction, so the event is the only thing a consumer needs.
 */
@Service
@Profile("command")
public class ShiftApprovalService {

    private final ShiftApprovalRepository approvals;
    private final ShiftAssignmentRepository assignments;
    private final ShiftRepository shifts;
    private final DomainEvents events;

    ShiftApprovalService(ShiftApprovalRepository approvals, ShiftAssignmentRepository assignments,
                         ShiftRepository shifts, DomainEvents events) {
        this.approvals = approvals;
        this.assignments = assignments;
        this.shifts = shifts;
        this.events = events;
    }

    @Transactional
    public ShiftApproval decide(long shiftAssignmentId, long approverEmployeeId, ApprovalDecision decision,
                                String comment, String correlationId) {
        ShiftAssignment assignment = activeAssignment(shiftAssignmentId);
        if (approvals.findByShiftAssignmentId(shiftAssignmentId).isPresent()) {
            throw new AssignmentRejectedException("assignment " + shiftAssignmentId + " already has a decision");
        }

        ShiftApproval saved = approvals.save(new ShiftApproval(null, shiftAssignmentId, approverEmployeeId,
                decision, comment, Instant.now()));
        publishAssignmentAfterDecision(assignment, decision, correlationId);
        return saved;
    }

    /** PUT /v1/shiftapprovals/{id}: the decision can be corrected, and APPROVED still moves the assignment. */
    @Transactional
    public ShiftApproval replace(long shiftApprovalId, ApprovalDecision decision, String comment,
                                 String correlationId) {
        ShiftApproval existing = approvals.findById(shiftApprovalId)
                .orElseThrow(() -> new ApprovalNotFoundException(shiftApprovalId));

        ShiftApproval saved = approvals.save(new ShiftApproval(existing.shiftApprovalId(),
                existing.shiftAssignmentId(), existing.approverEmployeeId(), decision, comment, Instant.now()));
        publishAssignmentAfterDecision(activeAssignment(existing.shiftAssignmentId()), decision, correlationId);
        return saved;
    }

    /** DELETE /v1/shiftapprovals/{id}: removes the decision, but never changes an assignment back. */
    @Transactional
    public void delete(long shiftApprovalId) {
        ShiftApproval existing = approvals.findById(shiftApprovalId)
                .orElseThrow(() -> new ApprovalNotFoundException(shiftApprovalId));
        approvals.delete(existing);
    }

    private void publishAssignmentAfterDecision(ShiftAssignment assignment, ApprovalDecision decision,
                                                String correlationId) {
        Shift shift = shifts.findById(assignment.shiftId())
                .filter(found -> !found.deleted())
                .orElseThrow(() -> new ShiftNotFoundException(assignment.shiftId()));

        ShiftAssignment current = assignment;
        if (decision == ApprovalDecision.APPROVED && assignment.status() != AssignmentStatus.APPROVED) {
            current = assignments.save(assignment.withStatus(AssignmentStatus.APPROVED,
                    assignment.checkInDatetime(), assignment.checkOutDatetime()));
        }

        // The contract says this endpoint publishes assignment.updated.v1; on a rejection the snapshot
        // is unchanged, so a consumer'"'"'s version rule ignores it.
        events.append(DomainEvents.ASSIGNMENT_UPDATED, current.shiftAssignmentId(),
                current.aggregateVersion(), correlationId, current.toSnapshot(shift));
    }

    private ShiftAssignment activeAssignment(long shiftAssignmentId) {
        return assignments.findById(shiftAssignmentId)
                .filter(assignment -> !assignment.deleted())
                .orElseThrow(() -> new AssignmentNotFoundException(shiftAssignmentId));
    }
}
