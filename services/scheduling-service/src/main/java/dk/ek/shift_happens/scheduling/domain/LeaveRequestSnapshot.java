package dk.ek.shift_happens.scheduling.domain;

import java.time.LocalDate;

/**
 * The part of leave.request.approval-started.v1 that Scheduling needs, matching
 * contracts/events/payloads/leave-request.schema.json. Extra fields in the payload are ignored.
 */
public record LeaveRequestSnapshot(
        Long leaveRequestId,
        Long employeeId,
        LocalDate startDate,
        LocalDate endDate,
        String requestStatus) {
}
