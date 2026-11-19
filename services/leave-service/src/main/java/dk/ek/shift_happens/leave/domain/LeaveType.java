package dk.ek.shift_happens.leave.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * A kind of leave (vacation, sick leave, ...). Every change bumps {@code aggregateVersion} and is announced with
 * leave.type.*.v1; a delete is soft ({@code deletedAt}), so requests and ledger entries that refer to the type
 * stay valid.
 */
public record LeaveType(
        Long leaveTypeId,
        String leaveTypeName,
        String leaveTypeDescription,
        boolean requiresApproval,
        boolean paidLeave,
        long aggregateVersion,
        Instant deletedAt) {

    public LeaveType {
        if (leaveTypeName == null || leaveTypeName.isBlank()) {
            throw new InvalidLeaveRequestException("leaveTypeName must not be blank");
        }
    }

    /** A new, not yet stored type (version 1). */
    public LeaveType(Long leaveTypeId, String leaveTypeName, String leaveTypeDescription, boolean requiresApproval,
                     boolean paidLeave) {
        this(leaveTypeId, leaveTypeName, leaveTypeDescription, requiresApproval, paidLeave, 1, null);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** PUT: the new attributes, one version later; this very instance when nothing changes (no event). */
    public LeaveType changedTo(String name, String description, boolean needsApproval, boolean paid) {
        if (leaveTypeName.equals(name) && Objects.equals(leaveTypeDescription, description)
                && requiresApproval == needsApproval && paidLeave == paid) {
            return this;
        }
        return new LeaveType(leaveTypeId, name, description, needsApproval, paid, aggregateVersion + 1, deletedAt);
    }

    /** DELETE: soft delete, one version later; this very instance when it is already deleted. */
    public LeaveType deleted(Instant now) {
        if (isDeleted()) {
            return this;
        }
        return new LeaveType(leaveTypeId, leaveTypeName, leaveTypeDescription, requiresApproval, paidLeave,
                aggregateVersion + 1, now);
    }

    /** data of leave.type.created.v1 and leave.type.updated.v1 (leave-type.schema.json). */
    public LeaveTypeSnapshot snapshot() {
        if (leaveTypeId == null) {
            throw new IllegalStateException("a leave type must be saved before its snapshot can be published");
        }
        return new LeaveTypeSnapshot(leaveTypeId, leaveTypeName, leaveTypeDescription, requiresApproval, paidLeave);
    }

    /** data of leave.type.deleted.v1 (tombstone.schema.json). */
    public Tombstone tombstone() {
        if (deletedAt == null || leaveTypeId == null) {
            throw new IllegalStateException("leave type " + leaveTypeId + " is not a stored, deleted type");
        }
        return new Tombstone("leaveType", leaveTypeId, deletedAt);
    }
}
