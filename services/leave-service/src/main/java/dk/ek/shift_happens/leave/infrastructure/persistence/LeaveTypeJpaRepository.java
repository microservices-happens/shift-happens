package dk.ek.shift_happens.leave.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** Spring Data repository for leave_type. */
interface LeaveTypeJpaRepository extends JpaRepository<LeaveTypeEntity, Long> {

    @Query("select count(t) > 0 from LeaveTypeEntity t where t.deletedAt is null "
            + "and lower(t.leaveTypeName) = lower(:name) "
            + "and (:excludedId is null or t.leaveTypeId <> :excludedId)")
    boolean existsByNameExcluding(@Param("name") String name, @Param("excludedId") Long excludedId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from LeaveTypeEntity t where t.leaveTypeId = :id")
    Optional<LeaveTypeEntity> findForUpdate(@Param("id") long id);
}
