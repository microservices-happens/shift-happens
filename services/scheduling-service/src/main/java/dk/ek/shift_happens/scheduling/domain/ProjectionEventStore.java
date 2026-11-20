package dk.ek.shift_happens.scheduling.domain;

import java.util.List;
import java.util.UUID;

/**
 * Port for the projection event log plus the processed-event markers (idempotent consumer).
 *
 * <p>{@code isProcessed}/{@code markProcessed} dedupe redeliveries; {@code append}/{@code replayOrder}
 * make the read model rebuildable. A rebuild clears the markers but never the log.
 */
public interface ProjectionEventStore {

    boolean isProcessed(UUID eventId);

    void markProcessed(UUID eventId);

    void append(ProjectionEvent event);

    /** Oldest first, so a replay applies the same order the live projection saw. */
    List<ProjectionEvent> replayOrder();

    void clearProcessed();
}
