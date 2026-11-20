package dk.ek.shift_happens.identity.infrastructure.messaging;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1.6: identity only consumes events, so the round trip starts from a contract-valid message:
 * it deserializes into {@link EventEnvelope} without loss (correlationId included) and serializes back
 * to a message the contract still accepts.
 */
class EventEnvelopeSchemaTest {

    private static final Path ENVELOPE_SCHEMA = Path.of("../../contracts/events/envelope.schema.json");

    /** Same settings as the ObjectMapper Spring Boot gives the listener: ISO dates, unknown fields ignored. */
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
    void aContractMessageRoundTripsTheEnvelope() throws Exception {
        JsonNode message = mapper.readTree("""
                {"eventId":"0b7c6c2e-4f7a-4d8e-9a3c-1f5e2d6b7a90",
                 "eventType":"workforce.employee.updated.v1",
                 "occurredAt":"2026-11-20T08:15:00Z",
                 "producer":"workforce-service",
                 "aggregateId":42,"aggregateVersion":3,
                 "correlationId":"c1d2e3f4-0000-4000-8000-000000000001",
                 "causationId":null,
                 "data":{"employeeId":42,"email":"ada@shift.dk"}}
                """);
        assertThat(schema.validate(message)).as("the fixture is a valid contract message").isEmpty();

        EventEnvelope envelope = mapper.treeToValue(message, EventEnvelope.class);
        JsonNode back = mapper.readTree(mapper.writeValueAsString(envelope));

        assertThat(envelope.correlationId()).isEqualTo("c1d2e3f4-0000-4000-8000-000000000001");
        assertThat(schema.validate(back)).isEmpty();
        assertThat(back).isEqualTo(message);
    }
}
