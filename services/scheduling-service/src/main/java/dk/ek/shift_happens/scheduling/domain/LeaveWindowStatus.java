package dk.ek.shift_happens.scheduling.domain;

import java.time.LocalDate;

/** APPROVED (the employee is away) or CANCELLED (the window no longer applies). */
public enum LeaveWindowStatus {
    APPROVED,
    CANCELLED;

    public boolean isApproved() {
        return this == APPROVED;
    }
}
