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

    /** The status named by a URL segment, case-insensitive; anything else is a 400, never "no rows". */
    public static LeaveRequestStatus parse(String text) {
        for (LeaveRequestStatus status : values()) {
            if (status.name().equalsIgnoreCase(text == null ? null : text.strip())) {
                return status;
            }
        }
        throw new InvalidLeaveRequestException("unknown status '" + text + "'; expected one of "
                + java.util.Arrays.toString(values()));
    }

    public boolean isOccupying() {
        return OCCUPYING.contains(this);
    }

    public boolean isPending() {
        return this == PENDING;
    }
}
