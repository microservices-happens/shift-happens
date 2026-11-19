package dk.ek.shift_happens.leave.application;

import dk.ek.shift_happens.leave.domain.LeaveType;
import dk.ek.shift_happens.leave.domain.LeaveTypeConflictException;
import dk.ek.shift_happens.leave.domain.LeaveTypeRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/**
 * Leave types. Each change and its leave.type.*.v1 event commit in one transaction (outbox).
 *
 * <p>Delete is soft: the row stays (requests and ledger entries keep a valid reference), the version is bumped and
 * leave.type.deleted.v1 carries the tombstone. A type that is used can be deleted; a deleted type cannot be used for
 * new requests or ledger entries (it counts as unknown there), but its existing requests carry on. PUT and DELETE
 * lock the type's row, so the versions of one type never collide.
 */
@Service
@Profile("command")
public class LeaveTypeService {

    private final LeaveTypeRepository types;
    private final DomainEvents events;
    private final Clock clock;

    LeaveTypeService(LeaveTypeRepository types, DomainEvents events, Clock clock) {
        this.types = types;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public LeaveType create(String name, String description, boolean requiresApproval, boolean paidLeave,
                            String correlationId) {
        requireFreeName(name, null);
        LeaveType saved = types.save(new LeaveType(null, name, description, requiresApproval, paidLeave));
        events.append(DomainEvents.LEAVE_TYPE_CREATED, saved.leaveTypeId(), saved.aggregateVersion(), correlationId,
                saved.snapshot());
        return saved;
    }

    /** PUT: a deleted type is unknown (404); a PUT that changes nothing changes nothing (no version, no event). */
    @Transactional
    public LeaveType replace(long leaveTypeId, String name, String description, boolean requiresApproval,
                             boolean paidLeave, String correlationId) {
        LeaveType current = types.findByIdForUpdate(leaveTypeId)
                .filter(type -> !type.isDeleted())
                .orElseThrow(() -> new LeaveTypeNotFoundException(leaveTypeId));
        LeaveType changed = current.changedTo(name, description, requiresApproval, paidLeave);
        if (changed == current) {
            return current;
        }
        requireFreeName(name, leaveTypeId);
        LeaveType saved = types.save(changed);
        events.append(DomainEvents.LEAVE_TYPE_UPDATED, saved.leaveTypeId(), saved.aggregateVersion(), correlationId,
                saved.snapshot());
        return saved;
    }

    /** DELETE: soft. An already deleted type is an idempotent no-op (no second event); an unknown id is a 404. */
    @Transactional
    public void delete(long leaveTypeId, String correlationId) {
        LeaveType current = types.findByIdForUpdate(leaveTypeId)
                .orElseThrow(() -> new LeaveTypeNotFoundException(leaveTypeId));
        LeaveType deleted = current.deleted(clock.instant());
        if (deleted == current) {
            return;
        }
        LeaveType saved = types.save(deleted);
        events.append(DomainEvents.LEAVE_TYPE_DELETED, saved.leaveTypeId(), saved.aggregateVersion(), correlationId,
                saved.tombstone());
    }

    private void requireFreeName(String name, Long ownId) {
        if (types.existsByNameExcluding(name, ownId)) {
            throw new LeaveTypeConflictException("a leave type named '" + name + "' already exists");
        }
    }
}
