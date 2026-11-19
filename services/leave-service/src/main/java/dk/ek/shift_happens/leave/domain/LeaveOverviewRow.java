package dk.ek.shift_happens.leave.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** One row of /v1/views/employee-leave-overview (contracts/openapi/leave.yaml, schema EmployeeLeaveOverview). */
public record LeaveOverviewRow(
        long employeeId,
        String employeeName,
        long leaveRequestId,
        String leaveTypeName,
        LocalDate startDate,
        LocalDate endDate,
        String requestStatus,
        BigDecimal balanceDays) {
}
