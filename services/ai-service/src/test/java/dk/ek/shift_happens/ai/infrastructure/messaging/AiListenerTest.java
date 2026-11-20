package dk.ek.shift_happens.ai.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.ai.application.SuggestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/** P1.6: the consumed event's correlationId is in the MDC while it is handled, and only then. */
class AiListenerTest {

    private static final byte[] EVENT = """
            {"eventType":"scheduling.shift.understaffed.v1","correlationId":"corr-42"}
            """.getBytes(StandardCharsets.UTF_8);

    private final SuggestionService service = mock(SuggestionService.class);
    private final AiListener listener = new AiListener(service, new ObjectMapper());

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void putsTheCorrelationIdInTheMdcWhileHandling() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        doAnswer(call -> {
            seen.set(MDC.get("correlationId"));
            return null;
        }).when(service).handle(any());

        listener.receive(EVENT);

        assertThat(seen.get()).isEqualTo("corr-42");
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    void clearsTheMdcWhenHandlingFails() throws Exception {
        doThrow(new IllegalArgumentException("bad")).when(service).handle(any());

        assertThatThrownBy(() -> listener.receive(EVENT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(MDC.get("correlationId")).isNull();
    }
}
