package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

/** Spring Data repository for shift_assignment. */
interface ShiftAssignmentJpaRepository extends JpaRepository<ShiftAssignmentEntity, Long> {

    /**
     * The overlap rule: active assignments of one employee whose shift overlaps [from, to).
     * The join is explicit because the two entities are not associated (assignment holds an id).
     */
    @Query("""
            select assignment from ShiftAssignmentEntity assignment, ShiftEntity shift
             where shift.shiftId = assignment.shiftId
               and assignment.employeeId = :employeeId
               and assignment.deleted = false
               and assignment.assignmentStatus in ('ASSIGNED', 'APPROVED', 'SWAPPED')
               and shift.startDatetime < :to
               and shift.endDatetime > :from
            """)
    List<ShiftAssignmentEntity> findActiveOverlapping(@Param("employeeId") long employeeId,
                                                      @Param("from") Instant from,
                                                      @Param("to") Instant to);
}
