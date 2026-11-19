package dk.ek.shift_happens.leave.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The overview, the approval view and the status parsing of the query role, as plain Java. */
class LeaveOverviewTest {

    private static final Instant T = Instant.parse("2027-01-01T10:00:00Z");

    private static LeaveRequestSnapshot request(long id, long employeeId, long typeId, String typeName, String start,
                                                String status) {
        return new LeaveRequestSnapshot(id, employeeId, typeId, typeName, LocalDate.parse(start),
                LocalDate.parse(start).plusDays(1), status, null, T, null, null);
    }

    @Test
    void oneRowPerRequestSortedByEmployeeThenStartDateThenRequest() {
        List<LeaveRequestSnapshot> requests = List.of(
                request(3, 2, 1, "Vacation", "2027-03-01", "PENDING"),
                request(2, 1, 1, "Vacation", "2027-02-01", "PENDING"),
                request(5, 1, 1, "Vacation", "2027-01-01", "APPROVED"),
                request(4, 1, 1, "Vacation", "2027-01-01", "REJECTED"));

        List<LeaveOverviewRow> rows = LeaveOverview.assemble(requests, Map.of(), Map.of(), Map.of());

        assertThat(rows).extracting(LeaveOverviewRow::leaveRequestId).containsExactly(4L, 5L, 2L, 3L);
    }

    @Test
    void theNameBalanceAndTypeNameComeFromTheReadModelWithFallbacks() {
        List<LeaveRequestSnapshot> requests = List.of(request(1, 10, 5, "Old name", "2027-01-01", "APPROVED"),
                request(2, 11, 6, "Snapshot name", "2027-01-01", "PENDING"));
        Map<Long, ProjectedEmployee> employees = Map.of(10L, new ProjectedEmployee(10, "Grace", "Hopper", false));
        Map<BalanceKey, BigDecimal> balances = Map.of(new BalanceKey(10, 5), new BigDecimal("7.5"));

        List<LeaveOverviewRow> rows = LeaveOverview.assemble(requests, employees, Map.of(5L, "Vacation"), balances);

        assertThat(rows.get(0).employeeName()).isEqualTo("Grace Hopper");
        assertThat(rows.get(0).leaveTypeName()).as("the leave type's current name wins").isEqualTo("Vacation");
        assertThat(rows.get(0).balanceDays()).isEqualTo(new BigDecimal("7.50"));
        assertThat(rows.get(1).employeeName()).as("employee not known yet").isNull();
        assertThat(rows.get(1).leaveTypeName()).as("type not known: the snapshot's name").isEqualTo("Snapshot name");
        assertThat(rows.get(1).balanceDays()).as("no ledger entries").isEqualTo(new BigDecimal("0.00"));
    }

    @Test
    void aBalanceIsPerEmployeeAndLeaveType() {
        List<LeaveRequestSnapshot> requests = List.of(request(1, 10, 5, "A", "2027-01-01", "PENDING"),
                request(2, 10, 6, "B", "2027-02-01", "PENDING"));
        Map<BalanceKey, BigDecimal> balances = Map.of(new BalanceKey(10, 5), new BigDecimal("3"),
                new BalanceKey(10, 6), new BigDecimal("-1.25"), new BalanceKey(11, 5), new BigDecimal("100"));

        List<LeaveOverviewRow> rows = LeaveOverview.assemble(requests, Map.of(), Map.of(), balances);

        assertThat(rows).extracting(LeaveOverviewRow::balanceDays)
                .containsExactly(new BigDecimal("3.00"), new BigDecimal("-1.25"));
    }

    @Test
    void rowsOfADeletedEmployeeAreLeftOut() {
        List<LeaveRequestSnapshot> requests = List.of(request(1, 10, 5, "A", "2027-01-01", "PENDING"),
                request(2, 11, 5, "A", "2027-01-01", "PENDING"));
        Map<Long, ProjectedEmployee> employees = Map.of(10L, new ProjectedEmployee(10, null, null, true),
                11L, new ProjectedEmployee(11, "Ada", "Lovelace", false));

        List<LeaveOverviewRow> rows = LeaveOverview.assemble(requests, employees, Map.of(), Map.of());

        assertThat(rows).extracting(LeaveOverviewRow::employeeId).containsExactly(11L);
    }

    @Test
    void daysAreShownWithTwoDecimals() {
        assertThat(LeaveOverview.balanceOf(null)).hasToString("0.00");
        assertThat(LeaveOverview.balanceOf(new BigDecimal("12"))).hasToString("12.00");
        assertThat(LeaveOverview.balanceOf(new BigDecimal("-0.5"))).hasToString("-0.50");
        assertThat(LeaveOverview.balanceOf(new BigDecimal("1.005"))).hasToString("1.01");
    }

    @Test
    void aFullNameUsesWhicheverPartsExist() {
        assertThat(new ProjectedEmployee(1, "Ada", "Lovelace", false).fullName()).isEqualTo("Ada Lovelace");
        assertThat(new ProjectedEmployee(1, "Ada", null, false).fullName()).isEqualTo("Ada");
        assertThat(new ProjectedEmployee(1, null, null, true).fullName()).isNull();
    }

    @Test
    void statusParsingIsCaseInsensitiveAndRejectsAnythingElse() {
        assertThat(LeaveRequestStatus.parse("approved")).isEqualTo(LeaveRequestStatus.APPROVED);
        assertThat(LeaveRequestStatus.parse("Approval_Pending")).isEqualTo(LeaveRequestStatus.APPROVAL_PENDING);
        assertThat(LeaveRequestStatus.parse(" CANCELLED ")).isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThatThrownBy(() -> LeaveRequestStatus.parse("DONE")).isInstanceOf(InvalidLeaveRequestException.class)
                .hasMessageContaining("DONE");
        assertThatThrownBy(() -> LeaveRequestStatus.parse("")).isInstanceOf(InvalidLeaveRequestException.class);
        assertThatThrownBy(() -> LeaveRequestStatus.parse(null)).isInstanceOf(InvalidLeaveRequestException.class);
    }

    @Test
    void anApprovalIsDerivedFromTheDecisionOfARequest() {
        LeaveDecision decision = new LeaveDecision(99, ApprovalDecision.APPROVED, "ok", T, 4L);
        LeaveRequestSnapshot decided = new LeaveRequestSnapshot(1, 10, 5, "A", LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-01-02"), "APPROVED", null, T, decision, null);

        assertThat(LeaveApprovalView.of(decided)).contains(new LeaveApprovalView(4L, 1, 99, "APPROVED", "ok", T));
        assertThat(LeaveApprovalView.of(request(2, 10, 5, "A", "2027-02-01", "PENDING"))).isEmpty();
    }

    @Test
    void approvalsWithoutAnIdSortLast() {
        LeaveApprovalView noId = new LeaveApprovalView(null, 1, 99, "REJECTED", null, T);
        LeaveApprovalView second = new LeaveApprovalView(8L, 2, 99, "REJECTED", null, T);
        LeaveApprovalView first = new LeaveApprovalView(3L, 3, 99, "REJECTED", null, T);

        assertThat(List.of(noId, second, first).stream().sorted(LeaveApprovalView.ORDER).toList())
                .containsExactly(first, second, noId);
    }
}
