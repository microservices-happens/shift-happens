package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** P1.6: every event carries the correlation id of the request or event that caused it. */
class DomainEventsTest {

    private final List<EventEnvelope> appended = new ArrayList<>();
    private final DomainEvents events = new DomainEvents(appended::add, "scheduling-service");

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void anExplicitCorrelationIdWins() {
        MDC.put("correlationId", "from-mdc");

        events.append(DomainEvents.SHIFT_CREATED, 1, 1, "from-caller", Map.of());

        assertThat(appended.getFirst().correlationId()).isEqualTo("from-caller");
    }

    @Test
    void withoutOneTheIdFromTheMdcIsCopied() {
        MDC.put("correlationId", "from-filter");

        events.append(DomainEvents.SHIFT_CREATED, 1, 1, null, Map.of());
        events.append(DomainEvents.SHIFT_UPDATED, 1, 2, " ", Map.of());

        assertThat(appended).extracting(EventEnvelope::correlationId).containsOnly("from-filter");
    }

    @Test
    void withNeitherANewIdIsGenerated() {
        events.append(DomainEvents.SHIFT_CREATED, 1, 1, null, Map.of());

        assertThat(UUID.fromString(appended.getFirst().correlationId())).isNotNull();
    }
}
