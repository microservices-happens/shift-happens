package dk.ek.shift_happens.scheduling.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.application.EventOutbox;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Adapter: stores the serialized envelope in the outbox table, in the caller's transaction. */
@Component
@Profile("command")
class JpaEventOutbox implements EventOutbox {

    private final OutboxEventJpaRepository repository;
    private final ObjectMapper objectMapper;

    JpaEventOutbox(OutboxEventJpaRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(EventEnvelope envelope) {
        try {
            repository.save(new OutboxEventEntity(
                    envelope.eventId(),
                    envelope.eventType(),
                    envelope.aggregateId(),
                    objectMapper.writeValueAsString(envelope),
                    envelope.occurredAt()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + envelope.eventId(), e);
        }
    }
}
