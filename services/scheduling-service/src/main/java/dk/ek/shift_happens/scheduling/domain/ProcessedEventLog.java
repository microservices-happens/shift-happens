package dk.ek.shift_happens.scheduling.domain;

import java.util.UUID;

/** Idempotent consumer support: every consumer records the eventIds it has applied. */
public interface ProcessedEventLog {

    boolean contains(UUID eventId);

    void record(UUID eventId);
}
