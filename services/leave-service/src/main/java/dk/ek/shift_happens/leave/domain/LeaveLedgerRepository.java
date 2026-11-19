package dk.ek.shift_happens.leave.domain;

import java.util.List;
import java.util.Optional;

/** Port for the append-only ledger and its balance snapshots. There is deliberately no update or delete. */
public interface LeaveLedgerRepository {

    /** Appends an entry; {@code sagaAttempt} is the leave-approval saga attempt of a RESERVATION/REVERSAL, else null. */
    LedgerEntry append(LedgerEntry entry, Integer sagaAttempt);

    void saveSnapshot(BalanceSnapshot snapshot);

    Optional<BalanceSnapshot> findNewestSnapshot(long employeeId, long leaveTypeId);

    /** The entries of the pair whose number is greater than {@code entryNumber}. */
    List<LedgerEntry> findEntriesAfter(long employeeId, long leaveTypeId, long entryNumber);

    /** The entry of this type that references the leave request in this saga attempt, if any. */
    Optional<LedgerEntry> findForLeaveRequest(long leaveRequestId, TransactionType type, int sagaAttempt);

    /** True when any ledger entry uses the leave type. */
    boolean existsByLeaveTypeId(long leaveTypeId);
}
