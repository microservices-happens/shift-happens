package dk.ek.shift_happens.leave.api.dto;

import dk.ek.shift_happens.leave.domain.LeaveOverviewRow;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Response body of /v1/views/employee-leave-overview (contracts/openapi/leave.yaml, schema EmployeeLeaveOverview). */
public record EmployeeLeaveOverviewResponse(
        long employeeId,
        String employeeName,
        long leaveRequestId,
        String leaveTypeName,
        LocalDate startDate,
        LocalDate endDate,
        String requestStatus,
        BigDecimal balanceDays) {

    public static EmployeeLeaveOverviewResponse from(LeaveOverviewRow row) {
        return new EmployeeLeaveOverviewResponse(row.employeeId(), row.employeeName(), row.leaveRequestId(),
                row.leaveTypeName(), row.startDate(), row.endDate(), row.requestStatus(), row.balanceDays());
    }
}
