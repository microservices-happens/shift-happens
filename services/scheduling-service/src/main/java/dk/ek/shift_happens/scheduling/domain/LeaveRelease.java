package dk.ek.shift_happens.scheduling.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * data of scheduling.leave-release.completed.v1 and .rejected.v1, matching
 * contracts/events/payloads/leave-release.schema.json. aggregateId = leaveRequestId.
 */
public record LeaveRelease(
        long leaveRequestId,
        long employeeId,
        LocalDate startDate,
        LocalDate endDate,
        List<Long> releasedAssignmentIds,
        String reason) {
}
