package dk.ek.shift_happens.leave.infrastructure.persistence;

import dk.ek.shift_happens.leave.domain.BalanceSnapshot;
import dk.ek.shift_happens.leave.domain.LeaveLedgerRepository;
import dk.ek.shift_happens.leave.domain.LedgerEntry;
import dk.ek.shift_happens.leave.domain.TransactionType;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Adapter: the append-only ledger and its snapshots on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaLeaveLedgerRepository implements LeaveLedgerRepository {

    private final LeaveLedgerJpaRepository ledger;
    private final BalanceSnapshotJpaRepository snapshots;

    JpaLeaveLedgerRepository(LeaveLedgerJpaRepository ledger, BalanceSnapshotJpaRepository snapshots) {
        this.ledger = ledger;
        this.snapshots = snapshots;
    }

    /** Flushes so the generated id is known and a constraint violation surfaces inside the transaction. */
    @Override
    public LedgerEntry append(LedgerEntry entry, Integer sagaAttempt) {
        return ledger.saveAndFlush(LeaveLedgerEntity.from(entry, sagaAttempt)).toDomain();
    }

    @Override
    public void saveSnapshot(BalanceSnapshot snapshot) {
        snapshots.saveAndFlush(BalanceSnapshotEntity.from(snapshot));
    }

    @Override
    public Optional<BalanceSnapshot> findNewestSnapshot(long employeeId, long leaveTypeId) {
        return snapshots.findFirstByEmployeeIdAndLeaveTypeIdOrderByEntryNumberDesc(employeeId, leaveTypeId)
                .map(BalanceSnapshotEntity::toDomain);
    }

    @Override
    public List<LedgerEntry> findEntriesAfter(long employeeId, long leaveTypeId, long entryNumber) {
        return ledger.findByEmployeeIdAndLeaveTypeIdAndEntryNumberGreaterThanOrderByEntryNumber(
                employeeId, leaveTypeId, entryNumber).stream().map(LeaveLedgerEntity::toDomain).toList();
    }

    @Override
    public Optional<LedgerEntry> findForLeaveRequest(long leaveRequestId, TransactionType type, int sagaAttempt) {
        return ledger.findFirstByReferenceEntityTypeAndReferenceEntityIdAndTransactionTypeAndSagaAttempt(
                LedgerEntry.LEAVE_REQUEST, leaveRequestId, type.name(), sagaAttempt).map(LeaveLedgerEntity::toDomain);
    }

    @Override
    public boolean existsByLeaveTypeId(long leaveTypeId) {
        return ledger.existsByLeaveTypeId(leaveTypeId);
    }
}
