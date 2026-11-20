package dk.ek.shift_happens.ai.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import dk.ek.shift_happens.ai.infrastructure.llm.OllamaClient;
import dk.ek.shift_happens.ai.infrastructure.persistence.SuggestionPersistence;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1.6: the ai.replacement.suggested.v1 envelope matches contracts/events/envelope.schema.json and
 * carries the correlationId of the understaffed event that caused it. The model is stubbed (it fails,
 * so the rule fallback answers).
 */
class SuggestionEnvelopeSchemaTest {

    private static final Path ENVELOPE_SCHEMA = Path.of("../../contracts/events/envelope.schema.json");

    /** Same settings as the ObjectMapper Spring Boot gives the service: ISO dates. */
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static JsonSchema schema;

    @BeforeAll
    static void loadSchema() throws Exception {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        try (InputStream in = Files.newInputStream(ENVELOPE_SCHEMA)) {
            schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
        }
    }

    @Test
    void theSuggestionEnvelopeRoundTripsTheContractAndKeepsTheCorrelationId() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), any(Object[].class))).thenReturn(false);
        OllamaClient llm = mock(OllamaClient.class);
        when(llm.generate(anyString(), any())).thenThrow(new IllegalStateException("model down"));
        SuggestionPersistence persistence = mock(SuggestionPersistence.class);
        SuggestionService service = new SuggestionService(jdbc, mapper, llm, persistence);
        UUID causeId = UUID.randomUUID();
        JsonNode understaffed = mapper.readTree("""
                {"eventId":"%s","eventType":"scheduling.shift.understaffed.v1",
                 "occurredAt":"2026-11-20T08:15:00Z","producer":"scheduling-service",
                 "aggregateId":12,"aggregateVersion":2,"correlationId":"corr-from-scheduling",
                 "causationId":null,
                 "data":{"shiftId":12,"jobRoleId":3,"candidates":[
                   {"employeeId":7,"hoursThisWeek":10,"weeklyContractHours":37},
                   {"employeeId":8,"hoursThisWeek":30,"weeklyContractHours":37}]}}
                """.formatted(causeId));

        service.handle(understaffed);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(persistence).saveIfNew(eq(causeId), anyLong(), anyString(), any(), eq("rule"), any(UUID.class),
                payload.capture());
        JsonNode output = mapper.readTree(payload.getValue());
        assertThat(schema.validate(output)).isEmpty();
        assertThat(output.path("correlationId").asText()).isEqualTo("corr-from-scheduling");
        assertThat(output.path("causationId").asText()).isEqualTo(causeId.toString());
    }
}
