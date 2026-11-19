package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.List;

/**
 * A shift as the query role keeps it in its Mongo read model (P2.10/P2.11), built from
 * scheduling.shift.created/updated.v1.
 *
 * <p>Plain Java on purpose: the document mapping lives in infrastructure/readmodel, so the projection
 * and the GraphQL layer never see a persistence annotation. {@code aggregateVersion} and
 * {@code deletedAt} are what make the projection commutative and tombstone-aware.
 */
public record ShiftView(
        long shiftId,
        long departmentId,
        long workLocationId,
        String shiftName,
        Instant startDatetime,
        Instant endDatetime,
        ShiftStatus status,
        List<RequiredJobRoleSnapshot> requiredJobRoles,
        long aggregateVersion,
        Instant deletedAt) {

    public boolean deleted() {
        return deletedAt != null;
    }

    /** Half-open overlap [start, end), the same rule the command role uses. */
    public boolean overlaps(Instant from, Instant to) {
        return startDatetime.isBefore(to) && from.isBefore(endDatetime);
    }

    /** scheduling.shift.deleted.v1: the row stays, the tombstone is kept, the version moves on. */
    public ShiftView tombstoned(long version, Instant deletedAt) {
        return new ShiftView(shiftId, departmentId, workLocationId, shiftName, startDatetime, endDatetime,
                status, requiredJobRoles, version, deletedAt);
    }
}
