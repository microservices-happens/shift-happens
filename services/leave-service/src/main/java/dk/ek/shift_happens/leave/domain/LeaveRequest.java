package dk.ek.shift_happens.leave.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Objects;

/**
 * A leave request and its state machine. Every rule lives here as plain Java, so it is testable without Spring.
 *
 * <p>{@code endDate} is the last day of leave, so [1st-5th] and [5th-7th] overlap and [1st-5th] and
 * [6th-7th] do not. {@code aggregateVersion} increments on every change so consumers can drop stale snapshots.
 * {@code decision} is derived from the stored approval and is null until a manager has decided.
 */
public record LeaveRequest(
        Long leaveRequestId,
        long employeeId,
        long leaveTypeId,
        LocalDate startDate,
        LocalDate endDate,
        LeaveRequestStatus status,
        String reason,
        Instant requestedDatetime,
        LeaveDecision decision,
        String rejectionReason,
        long aggregateVersion) {

    /**
     * POST /v1/leaverequests. Every request starts PENDING, whether or not the leave type requires approval:
     * auto-approval of types without approval is out of scope for #44.
     */
    public static LeaveRequest submit(long employeeId, long leaveTypeId, LocalDate startDate, LocalDate endDate,
                                      String reason, Instant now) {
        requireDates(startDate, endDate);
        return new LeaveRequest(null, employeeId, leaveTypeId, startDate, endDate, LeaveRequestStatus.PENDING,
                reason, now, null, null, 1);
    }

    /**
     * PATCH: a null argument keeps the current value. Only a PENDING request can be edited. An edit that changes
     * nothing returns this very instance (no version bump).
     */
    public LeaveRequest edited(Long newLeaveTypeId, LocalDate newStartDate, LocalDate newEndDate, String newReason) {
        requirePending("edited");
        LocalDate start = newStartDate != null ? newStartDate : startDate;
        LocalDate end = newEndDate != null ? newEndDate : endDate;
        requireDates(start, end);
        long type = newLeaveTypeId != null ? newLeaveTypeId : leaveTypeId;
        String text = newReason != null ? newReason : reason;
        if (type == leaveTypeId && start.equals(startDate) && end.equals(endDate) && Objects.equals(text, reason)) {
            return this; // nothing changes: same instance, same version
        }
        return new LeaveRequest(leaveRequestId, employeeId, type, start, end, status, text, requestedDatetime,
                decision, rejectionReason, aggregateVersion + 1);
    }

    /**
     * DELETE: PENDING or APPROVED to CANCELLED (soft, the row stays). Cancelling an already CANCELLED request is an
     * idempotent no-op and returns this instance. APPROVAL_PENDING cannot be cancelled: the saga is in flight and
     * would have to be compensated first. REJECTED is final. APPROVED leave can only be cancelled before its first
     * day: from {@code startDate} on (today is the UTC date) the days are taken and are not given back.
     */
    public LeaveRequest cancelled(LocalDate today) {
        if (status == LeaveRequestStatus.CANCELLED) {
            return this;
        }
        if (status == LeaveRequestStatus.APPROVAL_PENDING) {
            throw new LeaveRequestRejectedException("leave request " + leaveRequestId + " is APPROVAL_PENDING: its "
                    + "approval saga is in flight and the request cannot be cancelled until it has finished");
        }
        if (status != LeaveRequestStatus.APPROVED) {
            requirePending("cancelled");
        }
        if (status == LeaveRequestStatus.APPROVED && !startDate.isAfter(today)) {
            throw new LeaveRequestRejectedException("leave request " + leaveRequestId + " started on " + startDate
                    + ": approved leave can only be cancelled before its first day");
        }
        return withStatus(LeaveRequestStatus.CANCELLED, decision, rejectionReason);
    }

    /** A manager rejects a PENDING request; the comment becomes the rejection reason. */
    public LeaveRequest rejected(LeaveDecision rejection, String defaultReason) {
        requirePending("rejected");
        String reasonText = rejection.comment() == null || rejection.comment().isBlank()
                ? defaultReason
                : rejection.comment();
        return withStatus(LeaveRequestStatus.REJECTED, rejection, reasonText);
    }

    /** A manager approves a PENDING request: it waits (APPROVAL_PENDING) for Scheduling to release the employee. */
    public LeaveRequest approvalStarted(LeaveDecision approval) {
        requirePending("approved");
        return withStatus(LeaveRequestStatus.APPROVAL_PENDING, approval, null);
    }

    /** Saga step: Scheduling released the employee's assignments. */
    public LeaveRequest approved() {
        requireStatus(LeaveRequestStatus.APPROVAL_PENDING, "approved");
        return withStatus(LeaveRequestStatus.APPROVED, decision, null);
    }

    /** Saga compensation (a refusal from Scheduling or a timeout): the manager's decision stays as it was. */
    public LeaveRequest rejectedBySaga(String reasonText) {
        requireStatus(LeaveRequestStatus.APPROVAL_PENDING, "rejected by the saga");
        return withStatus(LeaveRequestStatus.REJECTED, decision, reasonText);
    }

    /** A late completed reply after a timeout compensation: REJECTED goes back to APPROVAL_PENDING, and only so. */
    public LeaveRequest approvalRetried() {
        requireStatus(LeaveRequestStatus.REJECTED, "retried");
        return withStatus(LeaveRequestStatus.APPROVAL_PENDING, decision, null);
    }

    /** Calendar days of leave, both ends included: weekends count (1st-1st is 1 day, 1st-5th is 5). */
    public long days() {
        return ChronoUnit.DAYS.between(startDate, endDate) + 1;
    }

    /** True when the two closed day ranges share at least one day. */
    public boolean overlaps(LocalDate otherStart, LocalDate otherEnd) {
        return !startDate.isAfter(otherEnd) && !otherStart.isAfter(endDate);
    }

    /** The employee may not have another occupying request on any of these days. */
    public void requireNoOverlapWith(Collection<LeaveRequest> others) {
        for (LeaveRequest other : others) {
            boolean itself = leaveRequestId != null && leaveRequestId.equals(other.leaveRequestId());
            if (!itself && other.employeeId == employeeId && other.status.isOccupying()
                    && overlaps(other.startDate, other.endDate)) {
                throw new LeaveRequestRejectedException("employee " + employeeId + " already has leave request "
                        + other.leaveRequestId + " (" + other.status + ") from " + other.startDate + " to "
                        + other.endDate);
            }
        }
    }

    public LeaveRequestSnapshot snapshot(String leaveTypeName) {
        if (leaveRequestId == null) {
            throw new IllegalStateException("a leave request must be saved before its snapshot can be published");
        }
        return new LeaveRequestSnapshot(leaveRequestId, employeeId, leaveTypeId, leaveTypeName, startDate, endDate,
                status.name(), reason, requestedDatetime, decision, rejectionReason);
    }

    private LeaveRequest withStatus(LeaveRequestStatus target, LeaveDecision newDecision, String newRejection) {
        return new LeaveRequest(leaveRequestId, employeeId, leaveTypeId, startDate, endDate, target, reason,
                requestedDatetime, newDecision, newRejection, aggregateVersion + 1);
    }

    private void requirePending(String action) {
        requireStatus(LeaveRequestStatus.PENDING, action);
    }

    private void requireStatus(LeaveRequestStatus required, String action) {
        if (status != required) {
            throw new LeaveRequestRejectedException("leave request " + leaveRequestId + " is " + status
                    + " and cannot be " + action + "; only " + required + " requests can");
        }
    }

    private static void requireDates(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new InvalidLeaveRequestException("startDate and endDate are required");
        }
        if (endDate.isBefore(startDate)) {
            throw new InvalidLeaveRequestException("endDate " + endDate + " is before startDate " + startDate);
        }
    }
}
