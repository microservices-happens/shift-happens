package dk.ek.shift_happens.scheduling.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** P1.6: the envelope this service publishes round-trips contracts/events/envelope.schema.json. */
class EventEnvelopeSchemaTest {

    private static final Path ENVELOPE_SCHEMA = Path.of("../../contracts/events/envelope.schema.json");

    /** Same settings as the ObjectMapper Spring Boot gives the outbox: ISO dates, unknown fields ignored. */
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
    void anEnvelopeRoundTripsTheContract() throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                "scheduling.shift.updated.v1",
                Instant.parse("2026-11-20T08:15:00Z"),
                "scheduling-service",
                42,
                3,
                "c1d2e3f4-0000-4000-8000-000000000001",
                null,
                Map.of("shiftId", 42, "shiftName", "Morning"));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(envelope));

        assertThat(schema.validate(json)).isEmpty();
        assertThat(json.path("correlationId").asText()).isEqualTo("c1d2e3f4-0000-4000-8000-000000000001");
        EventEnvelope back = mapper.treeToValue(json, EventEnvelope.class);
        assertThat(back).usingRecursiveComparison().ignoringFields("data").isEqualTo(envelope);
        assertThat(mapper.convertValue(back.data(), Map.class)).isEqualTo(envelope.data());
    }

    @Test
    void theContractRejectsAnEnvelopeWithoutCorrelationId() throws Exception {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "scheduling.shift.created.v1",
                Instant.now(), "scheduling-service", 1, 1, null, null, Map.of());

        JsonNode json = mapper.readTree(mapper.writeValueAsString(envelope));

        assertThat(schema.validate(json)).isNotEmpty();
    }
}
