package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.ShiftAssignment;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignmentRepository;
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
}
