package dk.ek.shift_happens.leave.domain;

import java.time.Instant;
import java.time.LocalDate;

/** data of leave.request.*.v1 (contracts/events/payloads/leave-request.schema.json): a full snapshot. */
public record LeaveRequestSnapshot(
        long leaveRequestId,
        long employeeId,
        long leaveTypeId,
        String leaveTypeName,
        LocalDate startDate,
        LocalDate endDate,
        String requestStatus,
        String reason,
        Instant requestedDatetime,
        LeaveDecision decision,
        String rejectionReason) {
}
