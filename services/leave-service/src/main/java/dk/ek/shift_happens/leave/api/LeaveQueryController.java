package dk.ek.shift_happens.leave.api;

import dk.ek.shift_happens.leave.api.dto.EmployeeLeaveOverviewResponse;
import dk.ek.shift_happens.leave.api.dto.LeaveApprovalResponse;
import dk.ek.shift_happens.leave.api.dto.LeaveLedgerEntryResponse;
import dk.ek.shift_happens.leave.api.dto.LeaveRequestResponse;
import dk.ek.shift_happens.leave.api.dto.LeaveTypeResponse;
import dk.ek.shift_happens.leave.application.LeaveQueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Every GET of contracts/openapi/leave.yaml, answered from the Mongo read model of the query role. There is no
 * write endpoint here: POST, PUT, PATCH and DELETE are a 405 problem+json (writes belong to the command role).
 */
@RestController
@Profile("query")
public class LeaveQueryController {

    private final LeaveQueryService queries;

    LeaveQueryController(LeaveQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/v1/leavetypes")
    public List<LeaveTypeResponse> leaveTypes() {
        return queries.leaveTypes().stream().map(LeaveTypeResponse::from).toList();
    }

    @GetMapping("/v1/leavetypes/{id}")
    public LeaveTypeResponse leaveType(@PathVariable long id) {
        return LeaveTypeResponse.from(queries.leaveType(id));
    }

    @GetMapping("/v1/leaverequests")
    public List<LeaveRequestResponse> leaveRequests() {
        return queries.leaveRequests().stream().map(LeaveRequestResponse::from).toList();
    }

    @GetMapping("/v1/leaverequests/{id}")
    public LeaveRequestResponse leaveRequest(@PathVariable long id) {
        return LeaveRequestResponse.from(queries.leaveRequest(id));
    }

    @GetMapping("/v1/leaveapprovals")
    public List<LeaveApprovalResponse> leaveApprovals() {
        return queries.approvals().stream().map(LeaveApprovalResponse::from).toList();
    }

    @GetMapping("/v1/leaveapprovals/{id}")
    public LeaveApprovalResponse leaveApproval(@PathVariable long id) {
        return LeaveApprovalResponse.from(queries.approval(id));
    }

    @GetMapping("/v1/leaveapprovals/request/{leaveRequestId}")
    public List<LeaveApprovalResponse> leaveApprovalsOfRequest(@PathVariable long leaveRequestId) {
        return queries.approvalsOfRequest(leaveRequestId).stream().map(LeaveApprovalResponse::from).toList();
    }

    @GetMapping("/v1/leaveledgers")
    public List<LeaveLedgerEntryResponse> leaveLedgers() {
        return queries.ledgerEntries().stream().map(LeaveLedgerEntryResponse::from).toList();
    }

    @GetMapping("/v1/leaveledgers/{id}")
    public LeaveLedgerEntryResponse leaveLedger(@PathVariable long id) {
        return LeaveLedgerEntryResponse.from(queries.ledgerEntry(id));
    }

    @GetMapping("/v1/views/employee-leave-overview")
    public List<EmployeeLeaveOverviewResponse> overview() {
        return overview(queries.overview());
    }

    @GetMapping("/v1/views/employee-leave-overview/employee/{employeeId}")
    public List<EmployeeLeaveOverviewResponse> overviewOfEmployee(@PathVariable long employeeId) {
        return overview(queries.overviewOfEmployee(employeeId));
    }

    @GetMapping("/v1/views/employee-leave-overview/status/{status}")
    public List<EmployeeLeaveOverviewResponse> overviewWithStatus(@PathVariable String status) {
        return overview(queries.overviewWithStatus(status));
    }

    @GetMapping("/v1/views/employee-leave-overview/leave-type/{leaveTypeName}")
    public List<EmployeeLeaveOverviewResponse> overviewOfLeaveType(@PathVariable String leaveTypeName) {
        return overview(queries.overviewOfLeaveType(leaveTypeName));
    }

    private static List<EmployeeLeaveOverviewResponse> overview(List<dk.ek.shift_happens.leave.domain.LeaveOverviewRow> rows) {
        return rows.stream().map(EmployeeLeaveOverviewResponse::from).toList();
    }
}
