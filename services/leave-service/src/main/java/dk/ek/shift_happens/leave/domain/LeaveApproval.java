package dk.ek.shift_happens.leave.domain;

import java.time.Instant;

/** A manager's stored decision about a leave request. */
public record LeaveApproval(
        Long leaveApprovalId,
        long leaveRequestId,
        long approverEmployeeId,
        ApprovalDecision decision,
        String leaveComment,
        Instant decisionDatetime) {

    /**
     * PUT /v1/leaveapprovals/{id}. Every stored approval is a REJECTED decision on a request that is now
     * REJECTED, and a final request cannot be reopened, so a stored decision can never be changed today.
     * #47 revisits this when approvals can still be in flight.
     */
    public void requireChangeable() {
        throw new LeaveRequestRejectedException("leave approval " + leaveApprovalId + " is already final ("
                + decision + ") and cannot be changed");
    }

    public LeaveDecision toDecision() {
        return new LeaveDecision(approverEmployeeId, decision, leaveComment, decisionDatetime);
    }
}
