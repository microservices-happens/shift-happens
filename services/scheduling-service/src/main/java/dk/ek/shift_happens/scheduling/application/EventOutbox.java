package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;

/**
 * Port for the transactional outbox: the state change and the event are written in one transaction
 * and a relay publishes afterwards (contracts/README.md, "Outbox"). A state change must never be
 * committed without its event.
 */
public interface EventOutbox {

    void append(EventEnvelope envelope);
}
