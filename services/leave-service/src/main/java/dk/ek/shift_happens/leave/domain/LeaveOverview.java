package dk.ek.shift_happens.leave.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds the employee leave overview from the read model's parts, as plain Java.
 *
 * <p>One row per leave request. Rows of a deleted (tombstoned) employee are left out, as the monolith's view joined the
 * employee table; they stay in /v1/leaverequests. An employee that is not known yet has no name. The leave type name
 * comes from the leave type whenever its name is known (also after it is deleted: the command side keeps the row and
 * its name), else from the request's snapshot.
 */
public final class LeaveOverview {

    /** Sorted by employee, then start date, then request. */
    public static final Comparator<LeaveOverviewRow> ORDER = Comparator
            .comparingLong(LeaveOverviewRow::employeeId)
            .thenComparing(LeaveOverviewRow::startDate)
            .thenComparingLong(LeaveOverviewRow::leaveRequestId);

    private LeaveOverview() {
    }

    public static List<LeaveOverviewRow> assemble(List<LeaveRequestSnapshot> requests,
                                                  Map<Long, ProjectedEmployee> employees,
                                                  Map<Long, String> leaveTypeNames,
                                                  Map<BalanceKey, BigDecimal> balances) {
        return requests.stream()
                .filter(request -> {
                    ProjectedEmployee employee = employees.get(request.employeeId());
                    return employee == null || !employee.deleted();
                })
                .map(request -> {
                    ProjectedEmployee employee = employees.get(request.employeeId());
                    String typeName = leaveTypeNames.getOrDefault(request.leaveTypeId(), request.leaveTypeName());
                    return new LeaveOverviewRow(request.employeeId(),
                            employee == null ? null : employee.fullName(),
                            request.leaveRequestId(), typeName, request.startDate(), request.endDate(),
                            request.requestStatus(),
                            balanceOf(balances.get(new BalanceKey(request.employeeId(), request.leaveTypeId()))));
                })
                .sorted(ORDER)
                .toList();
    }

    /** Days are shown with two decimals; no entries at all is a balance of 0.00. */
    public static BigDecimal balanceOf(BigDecimal sum) {
        return (sum == null ? BigDecimal.ZERO : sum).setScale(2, RoundingMode.HALF_UP);
    }
}
