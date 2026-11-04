package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.domain.AssignmentStatus;
import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignment;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignmentRepository;
import dk.ek.shift_happens.scheduling.domain.ShiftRepository;
import dk.ek.shift_happens.scheduling.domain.Tombstone;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Assignments: who works a shift (P2.7). The qualification and overlap rules live in
 * {@link AssignmentPolicy}, which a swap also uses.
 */
@Service
@Profile("command")
public class ShiftAssignmentService {

    private final ShiftRepository shifts;
    private final ShiftAssignmentRepository assignments;
    private final AssignmentPolicy policy;
    private final DomainEvents events;

    ShiftAssignmentService(ShiftRepository shifts, ShiftAssignmentRepository assignments,
                           AssignmentPolicy policy, DomainEvents events) {
        this.shifts = shifts;
        this.assignments = assignments;
        this.policy = policy;
        this.events = events;
    }

    @Transactional
    public ShiftAssignment create(long shiftId, long employeeId, Instant now, String correlationId) {
        Shift shift = activeShift(shiftId);
        policy.requireAssignable(shift, employeeId);

        ShiftAssignment saved = assignments.save(ShiftAssignment.create(shiftId, employeeId, now));
        events.append(DomainEvents.ASSIGNMENT_CREATED, saved.shiftAssignmentId(), saved.aggregateVersion(),
                correlationId, saved.toSnapshot(shift));
        return saved;
    }

    @Transactional
    public ShiftAssignment update(long shiftAssignmentId, long expectedShiftId, long expectedEmployeeId,
                                  AssignmentStatus status, Instant checkIn, Instant checkOut,
                                  String correlationId) {
        ShiftAssignment current = activeAssignment(shiftAssignmentId);
        if (current.shiftId() != expectedShiftId || current.employeeId() != expectedEmployeeId) {
            throw new IllegalArgumentException("shiftId and employeeId must match assignment " + shiftAssignmentId);
        }
        Shift shift = shiftOf(current);

        ShiftAssignment updated = assignments.save(current.withStatus(status, checkIn, checkOut));
        events.append(DomainEvents.ASSIGNMENT_UPDATED, updated.shiftAssignmentId(), updated.aggregateVersion(),
                correlationId, updated.toSnapshot(shift));
        return updated;
    }

    @Transactional
    public ShiftAssignment delete(long shiftAssignmentId, String correlationId) {
        ShiftAssignment current = activeAssignment(shiftAssignmentId);
        ShiftAssignment deleted = assignments.save(current.markedDeleted());
        events.append(DomainEvents.ASSIGNMENT_DELETED, shiftAssignmentId, deleted.aggregateVersion(),
                correlationId, new Tombstone(Tombstone.ASSIGNMENT, shiftAssignmentId, Instant.now()));
        return deleted;
    }

    ShiftAssignment activeAssignment(long shiftAssignmentId) {
        return assignments.findById(shiftAssignmentId)
                .filter(assignment -> !assignment.deleted())
                .orElseThrow(() -> new AssignmentNotFoundException(shiftAssignmentId));
    }

    Shift shiftOf(ShiftAssignment assignment) {
        return shifts.findById(assignment.shiftId())
                .filter(shift -> !shift.deleted())
                .orElseThrow(() -> new ShiftNotFoundException(assignment.shiftId()));
    }

    private Shift activeShift(long shiftId) {
        return shifts.findById(shiftId)
                .filter(shift -> !shift.deleted())
                .orElseThrow(() -> new ShiftNotFoundException(shiftId));
    }
}
