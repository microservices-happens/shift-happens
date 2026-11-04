package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.ShiftAssignment;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignmentRepository;
import dk.ek.shift_happens.scheduling.domain.WorkedWindow;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Adapter: assignments on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaShiftAssignmentRepository implements ShiftAssignmentRepository {

    private final ShiftAssignmentJpaRepository jpa;

    JpaShiftAssignmentRepository(ShiftAssignmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public ShiftAssignment save(ShiftAssignment assignment) {
        return jpa.save(ShiftAssignmentEntity.from(assignment)).toDomain();
    }

    @Override
    public Optional<ShiftAssignment> findById(long shiftAssignmentId) {
        return jpa.findById(shiftAssignmentId).map(ShiftAssignmentEntity::toDomain);
    }

    @Override
    public List<ShiftAssignment> findActiveOverlapping(long employeeId, Instant from, Instant to) {
        return jpa.findActiveOverlapping(employeeId, from, to).stream()
                .map(ShiftAssignmentEntity::toDomain)
                .toList();
    }

    @Override
    public List<ShiftAssignment> findActiveByShift(long shiftId) {
        return jpa.findActiveByShift(shiftId).stream().map(ShiftAssignmentEntity::toDomain).toList();
    }

    @Override
    public List<ShiftAssignment> findActiveOverlappingOtherThanShift(long shiftId, Instant from, Instant to) {
        return jpa.findActiveOverlappingOtherThanShift(shiftId, from, to).stream()
                .map(ShiftAssignmentEntity::toDomain)
                .toList();
    }

    @Override
    public List<WorkedWindow> findWorkedWindows(long employeeId, Instant from, Instant to) {
        return jpa.findWorkedWindows(employeeId, from, to).stream()
                .map(view -> new WorkedWindow(view.getStart(), view.getEnd()))
                .toList();
    }
}
