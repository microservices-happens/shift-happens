package dk.ek.shift_happens.notification.domain;

import java.util.Set;

/**
 * The business rules of notification-service: which event becomes which notification, for whom, and
 * whether that notification should also be emailed. Plain Java so the rules are testable without Spring.
 *
 * <p>Recipients come straight from the event snapshot (the employee an assignment belongs to, both
 * parties of a swap, the requester of leave). Events that concern the schedule as a whole go to every
 * active manager, because employee snapshots carry the role but no manager-of-department relation; the
 * alternative would be a synchronous call to workforce, which the architecture forbids.
 */
public final class NotificationPolicy {

    /** Statuses of an assignment that the employee must hear about; check-in/out and ASSIGNED are noise. */
    private static final Set<String> NOTIFYING_ASSIGNMENT_STATUSES =
            Set.of("CANCELLED", "RELEASED_FOR_LEAVE", "SWAPPED");

    private NotificationPolicy() {
    }

    public static NotificationDraft assignmentCreated(long employeeId, String shiftName, String start, String end) {
        return new NotificationDraft(employeeId, "New shift assignment",
                "You are assigned to " + shiftName + " from " + start + " to " + end + ".");
    }

    public static boolean assignmentUpdateIsNotified(String assignmentStatus) {
        return assignmentStatus != null && NOTIFYING_ASSIGNMENT_STATUSES.contains(assignmentStatus);
    }

    public static NotificationDraft assignmentUpdated(long employeeId, long assignmentId, String assignmentStatus) {
        return new NotificationDraft(employeeId, "Shift assignment updated",
                "Assignment " + assignmentId + " is now " + assignmentStatus + ".");
    }

    public static NotificationDraft swapRequested(long employeeToId, long swapId, long employeeFromId) {
        return new NotificationDraft(employeeToId, "Shift swap request",
                "Employee " + employeeFromId + " asked to hand you a shift (swap " + swapId
                        + "). A manager still has to approve it.");
    }

    public static NotificationDraft swapApprovedForReceiver(long employeeToId, long swapId) {
        return new NotificationDraft(employeeToId, "Shift swap approved",
                "You received a shift: swap " + swapId + " was approved.");
    }

    public static NotificationDraft swapApprovedForGiver(long employeeFromId, long swapId) {
        return new NotificationDraft(employeeFromId, "Shift swap approved",
                "Your colleague took over your shift: swap " + swapId + " was approved.");
    }

    public static NotificationDraft swapRejected(long employeeFromId, long swapId, String reason) {
        return new NotificationDraft(employeeFromId, "Shift swap rejected",
                "Swap " + swapId + " was rejected" + reasonSuffix(reason) + ".");
    }

    public static NotificationDraft leaveSubmitted(long employeeId, String leaveType, String startDate, String endDate) {
        return new NotificationDraft(employeeId, "Leave request received",
                "Your " + leaveType + " request for " + startDate + " to " + endDate + " is waiting for a manager.");
    }

    public static NotificationDraft leaveApproved(long employeeId, String leaveType, String startDate, String endDate) {
        return new NotificationDraft(employeeId, "Leave approved",
                "Your " + leaveType + " request for " + startDate + " to " + endDate + " was approved.");
    }

    public static NotificationDraft leaveRejected(long employeeId, String leaveType, String startDate, String endDate,
                                                  String rejectionReason) {
        return new NotificationDraft(employeeId, "Leave rejected",
                "Your " + leaveType + " request for " + startDate + " to " + endDate + " was rejected"
                        + reasonSuffix(rejectionReason) + ".");
    }

    public static NotificationDraft understaffedForManager(String shiftName, String start, int missingCount) {
        return new NotificationDraft(0, "Shift understaffed",
                shiftName + " starting " + start + " is short of " + missingCount
                        + " employee(s). AI replacement suggestions are on their way.");
    }

    public static NotificationDraft shiftCancelledForManager(String shiftName, String start) {
        return new NotificationDraft(0, "Shift cancelled",
                shiftName + " starting " + start + " was cancelled. Check the schedule for the affected employees.");
    }

    public static NotificationDraft suggestionForManager(String shiftName, int missingCount, String topCandidateId,
                                                          String reason) {
        return new NotificationDraft(0, "Replacement suggestion ready",
                shiftName + " is short of " + missingCount + " employee(s). Top candidate: "
                        + (topCandidateId == null ? "none available" : "employee " + topCandidateId)
                        + ". " + reason);
    }

    /** The event type decides whether the notification is also requested by email. */
    public static boolean emailRequested(String eventType) {
        return !"scheduling.shift.updated.v1".equals(eventType);
    }

    private static String reasonSuffix(String reason) {
        return reason == null || reason.isBlank() ? "" : ": " + reason;
    }
}
