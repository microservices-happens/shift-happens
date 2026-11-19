package dk.ek.shift_happens.leave.domain;

import java.util.List;

/** Lifecycle of a leave request (contracts/events/payloads/leave-request.schema.json). */
public enum LeaveRequestStatus {
    PENDING,
    /** Manager approved; the saga waits for Scheduling to release the employee's assignments. */
    APPROVAL_PENDING,
    APPROVED,
    REJECTED,
    CANCELLED;

    /** The statuses that occupy the employee's calendar and therefore take part in the overlap rule. */
    public static final List<LeaveRequestStatus> OCCUPYING = List.of(PENDING, APPROVAL_PENDING, APPROVED);

    public boolean isOccupying() {
        return OCCUPYING.contains(this);
    }

    public boolean isPending() {
        return this == PENDING;
    }
}
