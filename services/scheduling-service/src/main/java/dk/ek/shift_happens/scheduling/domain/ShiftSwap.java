package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;

/**
 * An employee asking to hand an assignment to a colleague, and the manager's decision.
 *
 * <p>Approving a swap only changes this aggregate; the reassignment happens in the use case, in the
 * same transaction (one local transaction is the whole reason swaps live in Scheduling).
 */
public record ShiftSwap(
        Long shiftSwapId,
        long originalShiftAssignmentId,
        long shiftId,
        long employeeFromId,
        long employeeToId,
        SwapStatus status,
        String reason,
        Instant requestDatetime,
        SwapDecision decision,
        long aggregateVersion) {

    /** POST /v1/shiftswaps: only the holder of the assignment can ask, and not for themselves. */
    public static ShiftSwap request(ShiftAssignment assignment, long employeeFromId, long employeeToId,
                                    String reason, Instant requestedAt) {
        if (assignment.shiftAssignmentId() == null) {
            throw new IllegalStateException("the assignment must be saved before a swap can be requested");
        }
        if (assignment.employeeId() != employeeFromId) {
            throw new SwapRejectedException("assignment " + assignment.shiftAssignmentId()
                    + " belongs to employee " + assignment.employeeId() + ", not " + employeeFromId);
        }
        requireTwoDifferentEmployees(employeeFromId, employeeToId);
        if (!assignment.status().isActive()) {
            throw new SwapRejectedException("assignment " + assignment.shiftAssignmentId()
                    + " is " + assignment.status() + " and cannot be swapped");
        }
        return new ShiftSwap(null, assignment.shiftAssignmentId(), assignment.shiftId(), employeeFromId,
                employeeToId, SwapStatus.PENDING, reason, requestedAt, null, 1);
    }

    /** POST /v1/shiftswapapprovals: APPROVED or REJECTED, once. */
    public ShiftSwap decided(SwapDecision decision) {
        if (!status.isPending()) {
            throw new SwapRejectedException("swap " + shiftSwapId + " is already " + status);
        }
        SwapStatus target = decision.decision() == ApprovalDecision.APPROVED
                ? SwapStatus.APPROVED
                : SwapStatus.REJECTED;
        return new ShiftSwap(shiftSwapId, originalShiftAssignmentId, shiftId, employeeFromId, employeeToId,
                target, reason, requestDatetime, decision, aggregateVersion + 1);
    }

    /**
     * PUT /v1/shiftswapapprovals/{id}: the record of a decision can be corrected (who decided, with
     * which comment), but the decision itself is final.
     *
     * <p>Flipping it would undo a handover that has already been published as an assignment update, and
     * the event catalog defines no reversal event, so the aggregate refuses it instead of letting the
     * write model disagree with the read model.
     */
    public ShiftSwap decisionCorrected(SwapDecision corrected) {
        if (status.isPending() || decision == null) {
            throw new SwapRejectedException("swap " + shiftSwapId + " has no decision to correct yet");
        }
        if (corrected.decision() != decision.decision()) {
            throw new SwapRejectedException("swap " + shiftSwapId + " is already " + status
                    + "; a decision cannot be reversed because the catalog defines no reversal event");
        }
        return new ShiftSwap(shiftSwapId, originalShiftAssignmentId, shiftId, employeeFromId, employeeToId,
                status, reason, requestDatetime, corrected, aggregateVersion + 1);
    }

    /** PUT /v1/shiftswaps/{id}: another colleague or another reason, while still pending. */
    public ShiftSwap reRequested(long newEmployeeToId, String newReason) {
        if (!status.isPending()) {
            throw new SwapRejectedException("swap " + shiftSwapId + " is already " + status);
        }
        requireTwoDifferentEmployees(employeeFromId, newEmployeeToId);
        return new ShiftSwap(shiftSwapId, originalShiftAssignmentId, shiftId, employeeFromId, newEmployeeToId,
                status, newReason, requestDatetime, null, aggregateVersion + 1);
    }

    public SwapSnapshot snapshot() {
        if (shiftSwapId == null) {
            throw new IllegalStateException("a swap must be saved before its snapshot can be published");
        }
        return new SwapSnapshot(shiftSwapId, originalShiftAssignmentId, shiftId, employeeFromId, employeeToId,
                status.name(), reason, requestDatetime, decision);
    }

    private static void requireTwoDifferentEmployees(long from, long to) {
        if (from == to) {
            throw new SwapRejectedException("employee " + from + " cannot swap a shift with themselves");
        }
    }
}
