package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.domain.NewShift;
import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftRepository;
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
 * <p>Command role only: the query role serves GraphQL from its own read model and has no writes.
 */
@Service
@Profile("command")
public class ShiftService {

    public static final String SHIFT_CREATED = "scheduling.shift.created.v1";

    private final ShiftRepository shifts;
    private final EventOutbox outbox;
    private final String producer;

    ShiftService(ShiftRepository shifts, EventOutbox outbox,
                 @Value("${app.events.producer}") String producer) {
        this.shifts = shifts;
        this.outbox = outbox;
        this.producer = producer;
    }

    /**
     * Saves the shift and its required job roles and writes scheduling.shift.created.v1 to the
     * outbox in the same transaction, so the event cannot be lost and cannot exist without the
     * shift (transactional outbox, contracts/README.md).
     */
    @Transactional
    public Shift create(NewShift newShift, String correlationId) {
        Shift saved = shifts.save(Shift.create(newShift));

        outbox.append(new EventEnvelope(
                UUID.randomUUID(),
                SHIFT_CREATED,
                Instant.now(),
                producer,
                saved.shiftId(),
                saved.aggregateVersion(),
                correlationId == null || correlationId.isBlank()
                        ? UUID.randomUUID().toString()
                        : correlationId,
                // A command has no causing event; saga replies in P3.1 will set it.
                null,
                saved.snapshot()));

        return saved;
    }
}
