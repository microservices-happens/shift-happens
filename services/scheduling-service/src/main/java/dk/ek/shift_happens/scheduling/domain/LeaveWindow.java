package dk.ek.shift_happens.scheduling.domain;

import java.time.LocalDate;

/**
 * A copy of an approved leave window, built from leave.request.approved.v1 and .cancelled.v1.
 * Scheduling needs it so the understaffed candidates leave out employees who are away, without a
 * synchronous call to Leave (contracts/events/catalog.md).
 */
public record LeaveWindow(
        long leaveRequestId,
        long employeeId,
        LocalDate startDate,
        LocalDate endDate,
        LeaveWindowStatus status,
        long aggregateVersion) {

    /** Whole days, inclusive of both ends. */
    public boolean overlaps(LocalDate from, LocalDate to) {
        return !startDate.isAfter(to) && !from.isAfter(endDate);
    }

    public boolean accepts(long version) {
        return version > aggregateVersion;
    }
}
