package dk.ek.shift_happens.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dk.ek.shift_happens.ai.infrastructure.llm.OllamaClient;
import dk.ek.shift_happens.ai.infrastructure.persistence.SuggestionPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.http.HttpTimeoutException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The listener tries a message three times. The model call (up to LLM_TIMEOUT) must happen once per message,
 * not once per attempt, and a model failure is answered by the rule instead of failing the delivery.
 */
class SuggestionServiceRetryTest {

    private static final String ANSWER = """
            {"reason":"Model ranking","rankedCandidates":[{"employeeId":7,"rank":1},{"employeeId":8,"rank":2}]}
            """;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OllamaClient llm = mock(OllamaClient.class);
    private final SuggestionPersistence persistence = mock(SuggestionPersistence.class);
    private final SuggestionService service = new SuggestionService(jdbc, mapper, llm, persistence);

    @Test
    void aRetryAfterADatabaseFailureReusesTheModelAnswer() throws Exception {
        when(llm.generate(anyString(), anyList())).thenReturn(ANSWER);
        when(llm.model()).thenReturn("stub");
        doThrow(new DataAccessResourceFailureException("db down")).doNothing().when(persistence)
                .saveIfNew(any(), anyLong(), anyString(), anyString(), anyString(), any(), anyString());
        JsonNode event = event();
        ModelCallMemory memory = new InMemory();

        assertThatThrownBy(() -> service.handle(event, memory)).isInstanceOf(DataAccessResourceFailureException.class);
        service.handle(event, memory);

        verify(llm, times(1)).generate(anyString(), anyList());
        verify(persistence, times(2)).saveIfNew(eq(UUID.fromString(event.path("eventId").asText())), eq(42L),
                anyString(), eq(ANSWER), eq("llm"), any(), anyString());
    }

    @Test
    void aModelFailureIsAnsweredByTheRuleAndNotRetried() throws Exception {
        when(llm.generate(anyString(), anyList())).thenThrow(new HttpTimeoutException("request timed out"));
        doThrow(new DataAccessResourceFailureException("db down")).doNothing().when(persistence)
                .saveIfNew(any(), anyLong(), anyString(), any(), anyString(), any(), anyString());
        JsonNode event = event();
        ModelCallMemory memory = new InMemory();

        assertThatThrownBy(() -> service.handle(event, memory)).isInstanceOf(DataAccessResourceFailureException.class);
        service.handle(event, memory);

        verify(llm, times(1)).generate(anyString(), anyList());
        verify(persistence, times(2)).saveIfNew(any(), eq(42L), anyString(), isNull(), eq("rule"), any(),
                anyString());
    }

    @Test
    void withoutMemoryEveryCallMayCallTheModel() throws Exception {
        when(llm.generate(anyString(), anyList())).thenReturn(ANSWER);
        doNothing().when(persistence)
                .saveIfNew(any(), anyLong(), anyString(), anyString(), anyString(), any(), anyString());

        service.handle(event());
        service.handle(event());

        verify(llm, times(2)).generate(anyString(), anyList());
    }

    private JsonNode event() throws Exception {
        return mapper.readTree("""
                {"eventId":"%s","eventType":"scheduling.shift.understaffed.v1","aggregateId":42,
                 "aggregateVersion":2,"correlationId":"corr","data":{"shiftId":42,"jobRoleId":3,"candidates":[
                  {"employeeId":7,"hoursThisWeek":30,"weeklyContractHours":37},
                  {"employeeId":8,"hoursThisWeek":12,"weeklyContractHours":37}]}}
                """.formatted(UUID.randomUUID()));
    }

    private static final class InMemory implements ModelCallMemory {
        private final AtomicReference<ModelCall> call = new AtomicReference<>();

        @Override
        public ModelCall recall() {
            return call.get();
        }

        @Override
        public void remember(ModelCall modelCall) {
            call.set(modelCall);
        }
    }
}
