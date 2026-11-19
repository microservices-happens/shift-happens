package dk.ek.shift_happens.leave.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

/**
 * A leave approval as the query role serves it (contracts/openapi/leave.yaml, schema LeaveApproval). Approvals are not
 * an event of their own: a request has at most one final decision, carried by its snapshot, and the approval is
 * derived from it. {@code leaveApprovalId} is null for snapshots published before the id was added to the contract.
 */
public record LeaveApprovalView(
        Long leaveApprovalId,
        long leaveRequestId,
        long approverEmployeeId,
        String decision,
        String leaveComment,
        Instant decisionDatetime) {

    /** Lists: by approval id (unknown ids last), then by request. */
    public static final Comparator<LeaveApprovalView> ORDER = Comparator
            .comparing(LeaveApprovalView::leaveApprovalId, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingLong(LeaveApprovalView::leaveRequestId);

    public static Optional<LeaveApprovalView> of(LeaveRequestSnapshot request) {
        LeaveDecision decision = request.decision();
        if (decision == null) {
            return Optional.empty();
        }
        return Optional.of(new LeaveApprovalView(decision.leaveApprovalId(), request.leaveRequestId(),
                decision.approverEmployeeId(), decision.decision().name(), decision.comment(),
                decision.decisionDatetime()));
    }
}
