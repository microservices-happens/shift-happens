package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.domain.NewShift;
import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftRepository;
import dk.ek.shift_happens.scheduling.domain.Tombstone;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Use cases for shifts on the command side. Transaction boundaries live here, nowhere else.
 *
 * <p>Every use case below commits the state change and its event together (transactional outbox,
 * contracts/README.md), so an event can never exist without its shift and a shift can never exist
 * without its event.
 */
@Service
@Profile("command")
public class ShiftService {

    public static final String SHIFT_CREATED = "scheduling.shift.created.v1";
    public static final String SHIFT_UPDATED = "scheduling.shift.updated.v1";
    public static final String SHIFT_DELETED = "scheduling.shift.deleted.v1";

    private final ShiftRepository shifts;
    private final EventOutbox outbox;
    private final String producer;

    ShiftService(ShiftRepository shifts, EventOutbox outbox,
                 @Value("${app.events.producer}") String producer) {
        this.shifts = shifts;
        this.outbox = outbox;
        this.producer = producer;
    }

    @Transactional
    public Shift create(NewShift newShift, String correlationId) {
        Shift saved = shifts.save(Shift.create(newShift));
        outbox.append(envelope(SHIFT_CREATED, saved, correlationId));
        return saved;
    }

    @Transactional
    public Shift update(long shiftId, NewShift changes, String correlationId) {
        Shift updated = shifts.save(activeShift(shiftId).updated(changes));
        outbox.append(envelope(SHIFT_UPDATED, updated, correlationId));
        return updated;
    }

    @Transactional
    public Shift delete(long shiftId, String correlationId) {
        Shift deleted = shifts.save(activeShift(shiftId).markedDeleted());
        // A delete is a tombstone, not a snapshot: there is no state left to broadcast.
        outbox.append(envelope(SHIFT_DELETED, deleted, correlationId,
                new Tombstone(Tombstone.SHIFT, shiftId, Instant.now())));
        return deleted;
    }

    /** A soft-deleted shift behaves as if it never existed, which is what stops a resurrection. */
    private Shift activeShift(long shiftId) {
        return shifts.findById(shiftId)
                .filter(shift -> !shift.deleted())
                .orElseThrow(() -> new ShiftNotFoundException(shiftId));
    }

    private EventEnvelope envelope(String eventType, Shift shift, String correlationId) {
        return envelope(eventType, shift, correlationId, shift.snapshot());
    }

    private EventEnvelope envelope(String eventType, Shift shift, String correlationId, Object data) {
        return new EventEnvelope(
                UUID.randomUUID(),
                eventType,
                Instant.now(),
                producer,
                shift.shiftId(),
                shift.aggregateVersion(),
                correlationIdOf(correlationId),
                // A REST command has no causing event; saga replies in P3.1 will set it.
                null,
                data);
    }

    private static String correlationIdOf(String correlationId) {
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }
}
