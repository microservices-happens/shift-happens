package dk.ek.shift_happens.leave.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for leave_type. */
interface LeaveTypeJpaRepository extends JpaRepository<LeaveTypeEntity, Long> {

    @Query("select count(t) > 0 from LeaveTypeEntity t where lower(t.leaveTypeName) = lower(:name) "
            + "and (:excludedId is null or t.leaveTypeId <> :excludedId)")
    boolean existsByNameExcluding(@Param("name") String name, @Param("excludedId") Long excludedId);
}
