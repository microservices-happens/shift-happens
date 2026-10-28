package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;

/**
 * data of every context's *.deleted.v1 event, matching contracts/events/payloads/tombstone.schema.json.
 *
 * <p>It replaces the snapshot, because after a delete there is no current state to broadcast: the
 * point is that consumers keep the tombstone and never let a late update resurrect the entity.
 */
public record Tombstone(String entityType, long entityId, Instant deletedAt) {

    public static final String SHIFT = "shift";
}
