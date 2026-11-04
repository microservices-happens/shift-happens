package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * What Scheduling answered for one leave request (leave_release). Keeping it makes the saga reply
 * idempotent: a redelivered approval-started republishes this answer instead of releasing again.
 */
public record LeaveReleaseRecord(
        long leaveRequestId,
        long employeeId,
        LocalDate startDate,
        LocalDate endDate,
        LeaveReleaseStatus result,
        List<Long> releasedAssignmentIds,
        String reason,
        Instant createdAt) {

    public static LeaveReleaseRecord completed(long leaveRequestId, long employeeId, LocalDate startDate,
                                               LocalDate endDate, List<Long> releasedAssignmentIds,
                                               Instant now) {
        return new LeaveReleaseRecord(leaveRequestId, employeeId, startDate, endDate,
                LeaveReleaseStatus.COMPLETED, List.copyOf(releasedAssignmentIds), null, now);
    }

    public static LeaveReleaseRecord rejected(long leaveRequestId, long employeeId, LocalDate startDate,
                                              LocalDate endDate, String reason, Instant now) {
        return new LeaveReleaseRecord(leaveRequestId, employeeId, startDate, endDate,
                LeaveReleaseStatus.REJECTED, List.of(), reason, now);
    }

    public LeaveRelease payload() {
        return new LeaveRelease(leaveRequestId, employeeId, startDate, endDate, releasedAssignmentIds, reason);
    }
}
