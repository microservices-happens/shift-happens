package dk.ek.shift_happens.leave.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data repository for leave_ledger. */
interface LeaveLedgerJpaRepository extends JpaRepository<LeaveLedgerEntity, Long> {

    List<LeaveLedgerEntity> findByEmployeeIdAndLeaveTypeIdAndEntryNumberGreaterThanOrderByEntryNumber(
            long employeeId, long leaveTypeId, long entryNumber);

    Optional<LeaveLedgerEntity> findFirstByReferenceEntityTypeAndReferenceEntityIdAndTransactionType(
            String referenceEntityType, Long referenceEntityId, String transactionType);
}
