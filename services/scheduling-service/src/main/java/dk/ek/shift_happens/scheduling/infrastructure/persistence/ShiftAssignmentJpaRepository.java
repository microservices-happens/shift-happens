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

    /** Who is currently on this shift, for the staffing count of the understaffed event. */
    @Query("""
            select assignment from ShiftAssignmentEntity assignment
             where assignment.shiftId = :shiftId
               and assignment.deleted = false
               and assignment.assignmentStatus in ('ASSIGNED', 'APPROVED', 'SWAPPED')
            """)
    List<ShiftAssignmentEntity> findActiveByShift(@Param("shiftId") long shiftId);

    /** Who is already busy elsewhere in the window, so the candidates must leave them out. */
    @Query("""
            select assignment from ShiftAssignmentEntity assignment, ShiftEntity shift
             where shift.shiftId = assignment.shiftId
               and assignment.shiftId <> :shiftId
               and assignment.deleted = false
               and assignment.assignmentStatus in ('ASSIGNED', 'APPROVED', 'SWAPPED')
               and shift.startDatetime < :to
               and shift.endDatetime > :from
            """)
    List<ShiftAssignmentEntity> findActiveOverlappingOtherThanShift(@Param("shiftId") long shiftId,
                                                                   @Param("from") Instant from,
                                                                   @Param("to") Instant to);

    /** The shift windows one employee already works inside [from, to), for hoursThisWeek. */
    @Query("""
            select shift.startDatetime as start, shift.endDatetime as end
              from ShiftAssignmentEntity assignment, ShiftEntity shift
             where shift.shiftId = assignment.shiftId
               and assignment.employeeId = :employeeId
               and assignment.deleted = false
               and assignment.assignmentStatus in ('ASSIGNED', 'APPROVED', 'SWAPPED')
               and shift.startDatetime >= :from
               and shift.startDatetime < :to
            """)
    List<WorkedWindowView> findWorkedWindows(@Param("employeeId") long employeeId,
                                             @Param("from") Instant from,
                                             @Param("to") Instant to);

    /** Projection of one already-worked window. */
    interface WorkedWindowView {
        Instant getStart();

        Instant getEnd();
    }
}
