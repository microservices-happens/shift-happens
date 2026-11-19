package dk.ek.shift_happens.leave.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Spring Data repository for leave_request. */
interface LeaveRequestJpaRepository extends JpaRepository<LeaveRequestEntity, Long> {

    List<LeaveRequestEntity> findByEmployeeIdAndRequestStatusIn(long employeeId, Collection<String> statuses);

    boolean existsByLeaveTypeId(long leaveTypeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from LeaveRequestEntity r where r.leaveRequestId = :id")
    Optional<LeaveRequestEntity> findForUpdate(@Param("id") long id);

    /** Like {@link #findForUpdate} but a row that another transaction has locked is skipped (empty result). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")) // -2 = SKIP LOCKED
    @Query("select r from LeaveRequestEntity r where r.leaveRequestId = :id")
    Optional<LeaveRequestEntity> findForUpdateSkipLocked(@Param("id") long id);

    /**
     * Transaction-scoped advisory lock, one-argument (bigint) form: a different keyspace from the idempotency
     * lock, which uses the two-int form.
     */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(:employeeId) AS text)", nativeQuery = true)
    String lockEmployeeCalendar(@Param("employeeId") long employeeId);
}
