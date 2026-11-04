package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/** Spring Data repository for leave_window. */
interface LeaveWindowJpaRepository extends JpaRepository<LeaveWindowEntity, Long> {

    @Query("""
            select w from LeaveWindowEntity w
             where w.employeeId = :employeeId
               and w.status = 'APPROVED'
               and w.startDate <= :to
               and w.endDate >= :from
            """)
    List<LeaveWindowEntity> findApprovedOverlapping(@Param("employeeId") long employeeId,
                                                    @Param("from") LocalDate from,
                                                    @Param("to") LocalDate to);
}
