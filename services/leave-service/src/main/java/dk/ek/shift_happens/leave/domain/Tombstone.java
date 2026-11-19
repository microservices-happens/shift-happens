package dk.ek.shift_happens.leave.domain;

import java.time.Instant;

/** data of every *.deleted.v1 event (contracts/events/payloads/tombstone.schema.json). */
public record Tombstone(String entityType, long entityId, Instant deletedAt) {
}
