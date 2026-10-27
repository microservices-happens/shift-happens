package dk.ek.shift_happens.identity.domain;

import java.util.UUID;

/** Port: records handled event ids so a redelivered event has no second side effect. */
public interface ProcessedEventLog {

    boolean contains(UUID eventId);

    void record(UUID eventId);
}
