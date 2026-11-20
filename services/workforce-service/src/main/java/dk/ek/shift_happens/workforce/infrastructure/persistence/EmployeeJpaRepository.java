package dk.ek.shift_happens.workforce.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

interface EmployeeJpaRepository extends JpaRepository<EmployeeEntity, Long> {

    Optional<EmployeeEntity> findByEmployeeIdAndDeletedFalse(long employeeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EmployeeEntity e where e.employeeId = :id and e.deleted = false")
    Optional<EmployeeEntity> lockActiveById(@Param("id") long employeeId);

    @Modifying
    @Query("update EmployeeEntity e set e.deleted = true, e.aggregateVersion = :version where e.employeeId = :id")
    void softDelete(@Param("id") long employeeId, @Param("version") long aggregateVersion);

    @Query("""
            select e from EmployeeEntity e
            where e.deleted = false
              and (:status is null or e.employmentStatus = :status)
              and (:location is null or e.primaryWorkLocationId = :location)
              and (:role is null or e.userRole = :role)
              and locate(lower(coalesce(cast(:email as String), '')), lower(e.email)) > 0
              and locate(lower(coalesce(cast(:firstName as String), '')), lower(e.firstName)) > 0
              and locate(lower(coalesce(cast(:lastName as String), '')), lower(e.lastName)) > 0
            """)
    List<EmployeeEntity> findActive(@Param("status") String status, @Param("location") Long location,
                                    @Param("role") String role, @Param("email") String email,
                                    @Param("firstName") String firstName, @Param("lastName") String lastName);
}
