package dk.ek.shift_happens.leave.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Spring Data repository for balance_snapshot. */
interface BalanceSnapshotJpaRepository extends JpaRepository<BalanceSnapshotEntity, BalanceSnapshotEntity.Key> {

    Optional<BalanceSnapshotEntity> findFirstByEmployeeIdAndLeaveTypeIdOrderByEntryNumberDesc(long employeeId,
                                                                                              long leaveTypeId);
}
