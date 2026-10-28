package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.domain.NewShift;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRole;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleNotFoundException;
import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftRepository;
import dk.ek.shift_happens.scheduling.domain.Tombstone;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Use cases for shifts on the command side. Transaction boundaries live here, nowhere else.
 *
 * <p>Every use case commits the state change and its event together (transactional outbox,
 * contracts/README.md): an event can never exist without its shift, and a shift never changes
 * without its event.
 */
@Service
@Profile("command")
public class ShiftService {

    private final ShiftRepository shifts;
    private final DomainEvents events;

    ShiftService(ShiftRepository shifts, DomainEvents events) {
        this.shifts = shifts;
        this.events = events;
    }

    @Transactional
    public Shift create(NewShift newShift, String correlationId) {
        Shift saved = shifts.save(Shift.create(newShift));
        events.append(DomainEvents.SHIFT_CREATED, saved.shiftId(), saved.aggregateVersion(), correlationId,
                saved.snapshot());
        return saved;
    }

    @Transactional
    public Shift update(long shiftId, NewShift changes, String correlationId) {
        Shift updated = shifts.save(activeShift(shiftId).updated(changes));
        events.append(DomainEvents.SHIFT_UPDATED, updated.shiftId(), updated.aggregateVersion(), correlationId,
                updated.snapshot());
        return updated;
    }

    @Transactional
    public Shift delete(long shiftId, String correlationId) {
        Shift deleted = shifts.save(activeShift(shiftId).markedDeleted());
        // A delete is a tombstone, not a snapshot: there is no state left to broadcast.
        events.append(DomainEvents.SHIFT_DELETED, shiftId, deleted.aggregateVersion(), correlationId,
                new Tombstone(Tombstone.SHIFT, shiftId, Instant.now()));
        return deleted;
    }

    /** POST /v1/shiftrequiredjobroles: the shift owns the rows, so it owns the change and the version. */
    @Transactional
    public Shift addRequiredJobRole(long shiftId, RequiredJobRole role, String correlationId) {
        Shift updated = shifts.save(activeShift(shiftId).withRequiredJobRoleAdded(role));
        events.append(DomainEvents.SHIFT_UPDATED, updated.shiftId(), updated.aggregateVersion(), correlationId,
                updated.snapshot());
        return updated;
    }

    /** PUT /v1/shiftrequiredjobroles/{id}. */
    @Transactional
    public Shift replaceRequiredJobRole(long shiftRequiredJobRoleId, long expectedShiftId,
                                       RequiredJobRole replacement, String correlationId) {
        Shift current = shiftOfRequiredJobRole(shiftRequiredJobRoleId);
        if (current.shiftId() == null || current.shiftId() != expectedShiftId) {
            throw new IllegalArgumentException("shiftId " + expectedShiftId
                    + " does not own required job role " + shiftRequiredJobRoleId);
        }
        Shift updated = shifts.save(current.withRequiredJobRoleReplaced(shiftRequiredJobRoleId, replacement));
        events.append(DomainEvents.SHIFT_UPDATED, updated.shiftId(), updated.aggregateVersion(), correlationId,
                updated.snapshot());
        return updated;
    }

    /** DELETE /v1/shiftrequiredjobroles/{id}. */
    @Transactional
    public Shift removeRequiredJobRole(long shiftRequiredJobRoleId, String correlationId) {
        Shift current = shiftOfRequiredJobRole(shiftRequiredJobRoleId);
        Shift updated = shifts.save(current.withoutRequiredJobRole(shiftRequiredJobRoleId));
        events.append(DomainEvents.SHIFT_UPDATED, updated.shiftId(), updated.aggregateVersion(), correlationId,
                updated.snapshot());
        return updated;
    }

    /** A soft-deleted shift behaves as if it never existed, which is what stops a resurrection. */
    Shift activeShift(long shiftId) {
        return shifts.findById(shiftId)
                .filter(shift -> !shift.deleted())
                .orElseThrow(() -> new ShiftNotFoundException(shiftId));
    }

    private Shift shiftOfRequiredJobRole(long shiftRequiredJobRoleId) {
        return shifts.findByRequiredJobRoleId(shiftRequiredJobRoleId)
                .filter(shift -> !shift.deleted())
                .orElseThrow(() -> new RequiredJobRoleNotFoundException(shiftRequiredJobRoleId));
    }
}
