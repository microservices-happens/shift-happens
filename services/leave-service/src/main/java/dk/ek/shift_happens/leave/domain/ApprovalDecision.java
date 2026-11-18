package dk.ek.shift_happens.leave.domain;

/** A manager's verdict on a leave request (contracts/openapi/leave.yaml, schema LeaveApproval). */
public enum ApprovalDecision {
    APPROVED,
    REJECTED
}
