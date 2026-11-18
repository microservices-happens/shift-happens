package dk.ek.shift_happens.leave.api.dto;

import dk.ek.shift_happens.leave.domain.LeaveRequest;

import java.time.Instant;
import java.time.LocalDate;

/** Response body of the leave request endpoints (contracts/openapi/leave.yaml, schema LeaveRequest). */
public record LeaveRequestResponse(
        Long leaveRequestId,
        long employeeId,
        long leaveTypeId,
        LocalDate startDate,
        LocalDate endDate,
        String requestStatus,
        String reason,
        String rejectionReason,
        Instant requestedDatetime) {

    public static LeaveRequestResponse from(LeaveRequest request) {
        return new LeaveRequestResponse(request.leaveRequestId(), request.employeeId(), request.leaveTypeId(),
                request.startDate(), request.endDate(), request.status().name(), request.reason(),
                request.rejectionReason(), request.requestedDatetime());
    }
}
