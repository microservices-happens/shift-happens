package dk.ek.shift_happens.workforce.application;

import dk.ek.shift_happens.workforce.infrastructure.messaging.EventEnvelope;

/**
 * Port for the transactional outbox: the state change and the event are written in one transaction
 * and a relay publishes afterwards (contracts/README.md, "Outbox").
 */
public interface EventOutbox {

    void append(EventEnvelope envelope);
}
