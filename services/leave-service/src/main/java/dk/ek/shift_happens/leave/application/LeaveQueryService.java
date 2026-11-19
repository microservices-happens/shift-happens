package dk.ek.shift_happens.leave.application;

import dk.ek.shift_happens.leave.domain.LeaveApprovalView;
import dk.ek.shift_happens.leave.domain.LeaveOverview;
import dk.ek.shift_happens.leave.domain.LeaveOverviewRow;
import dk.ek.shift_happens.leave.domain.LeaveRequestSnapshot;
import dk.ek.shift_happens.leave.domain.LeaveRequestStatus;
import dk.ek.shift_happens.leave.domain.LeaveTypeSnapshot;
import dk.ek.shift_happens.leave.domain.LedgerEntryRecorded;
import dk.ek.shift_happens.leave.domain.ReadModelReader;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Reads of the query role: every GET of contracts/openapi/leave.yaml is answered from the Mongo read model. */
@Service
@Profile("query")
public class LeaveQueryService {

    private final ReadModelReader readModel;

    LeaveQueryService(ReadModelReader readModel) {
        this.readModel = readModel;
    }

    public List<LeaveTypeSnapshot> leaveTypes() {
        return readModel.activeLeaveTypes();
    }

    public LeaveTypeSnapshot leaveType(long leaveTypeId) {
        return readModel.activeLeaveType(leaveTypeId).orElseThrow(() -> new LeaveTypeNotFoundException(leaveTypeId));
    }

    public List<LeaveRequestSnapshot> leaveRequests() {
        return readModel.leaveRequests();
    }

    public LeaveRequestSnapshot leaveRequest(long leaveRequestId) {
        return readModel.leaveRequest(leaveRequestId)
                .orElseThrow(() -> new LeaveRequestNotFoundException(leaveRequestId));
    }

    public List<LeaveApprovalView> approvals() {
        return readModel.decidedLeaveRequests().stream()
                .flatMap(request -> LeaveApprovalView.of(request).stream())
                .sorted(LeaveApprovalView.ORDER)
                .toList();
    }

    /** Only approvals that carry an id are reachable by id (older snapshots have none). */
    public LeaveApprovalView approval(long leaveApprovalId) {
        return readModel.decidedLeaveRequestByApprovalId(leaveApprovalId)
                .flatMap(LeaveApprovalView::of)
                .orElseThrow(() -> new LeaveApprovalNotFoundException(leaveApprovalId));
    }

    /** An empty list, not a 404, when the request has no decision (or does not exist). */
    public List<LeaveApprovalView> approvalsOfRequest(long leaveRequestId) {
        return readModel.leaveRequest(leaveRequestId)
                .flatMap(LeaveApprovalView::of)
                .map(List::of)
                .orElse(List.of());
    }

    public List<LedgerEntryRecorded> ledgerEntries() {
        return readModel.ledgerEntries();
    }

    public LedgerEntryRecorded ledgerEntry(long leaveLedgerId) {
        return readModel.ledgerEntry(leaveLedgerId)
                .orElseThrow(() -> new LeaveLedgerEntryNotFoundException(leaveLedgerId));
    }

    public List<LeaveOverviewRow> overview() {
        return overview(null, null, null);
    }

    public List<LeaveOverviewRow> overviewOfEmployee(long employeeId) {
        return overview(employeeId, null, null);
    }

    /** {@code status} is parsed here: an unknown one is a 400, never an empty list. */
    public List<LeaveOverviewRow> overviewWithStatus(String status) {
        return overview(null, LeaveRequestStatus.parse(status), null);
    }

    public List<LeaveOverviewRow> overviewOfLeaveType(String leaveTypeName) {
        return overview(null, null, leaveTypeName);
    }

    private List<LeaveOverviewRow> overview(Long employeeId, LeaveRequestStatus status, String leaveTypeName) {
        List<LeaveRequestSnapshot> requests = readModel.leaveRequests(employeeId, status);
        Set<Long> employeeIds = requests.stream().map(LeaveRequestSnapshot::employeeId).collect(Collectors.toSet());
        Set<Long> typeIds = new HashSet<>();
        requests.forEach(request -> typeIds.add(request.leaveTypeId()));

        List<LeaveOverviewRow> rows = LeaveOverview.assemble(requests, readModel.employees(employeeIds),
                readModel.leaveTypeNames(typeIds), readModel.balances(employeeIds));
        if (leaveTypeName == null) {
            return rows;
        }
        return rows.stream()
                .filter(row -> row.leaveTypeName() != null && row.leaveTypeName().equalsIgnoreCase(leaveTypeName.strip()))
                .toList();
    }
}
