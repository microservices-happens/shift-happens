package dk.ek.shift_happens.leave.domain;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Port for the query role's reads. Lists are sorted by id; "active" leave types are the ones not deleted. */
public interface ReadModelReader {

    List<LeaveTypeSnapshot> activeLeaveTypes();

    Optional<LeaveTypeSnapshot> activeLeaveType(long leaveTypeId);

    List<LeaveRequestSnapshot> leaveRequests();

    Optional<LeaveRequestSnapshot> leaveRequest(long leaveRequestId);

    /** Requests of one employee, and/or in one status, sorted by id; null means "any". */
    List<LeaveRequestSnapshot> leaveRequests(Long employeeId, LeaveRequestStatus status);

    /** The requests that carry a decision. */
    List<LeaveRequestSnapshot> decidedLeaveRequests();

    Optional<LeaveRequestSnapshot> decidedLeaveRequestByApprovalId(long leaveApprovalId);

    List<LedgerEntryRecorded> ledgerEntries();

    Optional<LedgerEntryRecorded> ledgerEntry(long leaveLedgerId);

    /** Known employees (also deleted ones) among the given ids. */
    Map<Long, ProjectedEmployee> employees(Collection<Long> employeeIds);

    /** Names of the leave types among the given ids whose name is known, deleted ones included (the command side keeps
     * a deleted type's row and last name). */
    Map<Long, String> leaveTypeNames(Collection<Long> leaveTypeIds);

    /**
     * Balance = the sum of changeAmountDays of the ledger entries, per employee and leave type, computed when asked
     * (never "the latest balanceAfterDays that arrived"), so the arrival order of events cannot matter.
     */
    Map<BalanceKey, BigDecimal> balances(Set<Long> employeeIds);
}
