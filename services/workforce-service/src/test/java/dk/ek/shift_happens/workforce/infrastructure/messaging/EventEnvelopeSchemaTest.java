package dk.ek.shift_happens.workforce.infrastructure.messaging;

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
    private static final Path PAYLOAD_SCHEMAS = Path.of("../../contracts/events/payloads");

    /** Same settings as the ObjectMapper Spring Boot gives the outbox: ISO dates, unknown fields ignored. */
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static JsonSchema schema;
    private static JsonSchema employeeSchema;
    private static JsonSchema tombstoneSchema;

    @BeforeAll
    static void loadSchema() throws Exception {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = Files.newInputStream(ENVELOPE_SCHEMA)) {
            schema = factory.getSchema(in, config);
        }
        try (InputStream in = Files.newInputStream(PAYLOAD_SCHEMAS.resolve("employee.schema.json"))) {
            employeeSchema = factory.getSchema(in, config);
        }
        try (InputStream in = Files.newInputStream(PAYLOAD_SCHEMAS.resolve("tombstone.schema.json"))) {
            tombstoneSchema = factory.getSchema(in, config);
        }
    }

    @Test
    void anEnvelopeRoundTripsTheContract() throws Exception {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(),
                "workforce.employee.updated.v1",
                Instant.parse("2026-11-20T08:15:00Z"),
                "workforce-service",
                42,
                3,
                "c1d2e3f4-0000-4000-8000-000000000001",
                null,
                Map.of("employeeId", 42, "firstName", "Ada"));

        JsonNode json = mapper.readTree(mapper.writeValueAsString(envelope));

        assertThat(schema.validate(json)).isEmpty();
        assertThat(json.path("correlationId").asText()).isEqualTo("c1d2e3f4-0000-4000-8000-000000000001");
        EventEnvelope back = mapper.treeToValue(json, EventEnvelope.class);
        assertThat(back).usingRecursiveComparison().ignoringFields("data").isEqualTo(envelope);
        assertThat(mapper.convertValue(back.data(), Map.class)).isEqualTo(envelope.data());
    }

    @Test
    void theContractRejectsAnEnvelopeWithoutCorrelationId() throws Exception {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), "workforce.employee.created.v1",
                Instant.now(), "workforce-service", 1, 1, null, null, Map.of());

        JsonNode json = mapper.readTree(mapper.writeValueAsString(envelope));

        assertThat(schema.validate(json)).isNotEmpty();
    }

    @Test
    void anEmployeeSnapshotMatchesThePayloadContract() throws Exception {
        JsonNode payload = mapper.readTree("""
                {
                  "employeeId": 42,
                  "employeeNumber": "E-42",
                  "userRole": "Employee",
                  "firstName": "Ada",
                  "lastName": "Lovelace",
                  "email": "ada@shift.dk",
                  "phoneNumber": "+45 12345678",
                  "hireDate": "2024-01-01",
                  "employmentStatus": "ACTIVE",
                  "primaryWorkLocationId": 1,
                  "weeklyContractHours": 37.5,
                  "jobRoleIds": [1, 3]
                }
                """);

        assertThat(employeeSchema.validate(payload)).isEmpty();
    }

    @Test
    void anEmployeeSnapshotWithoutJobRolesIsRejected() throws Exception {
        JsonNode payload = mapper.readTree("""
                {
                  "employeeId": 42,
                  "employeeNumber": "E-42",
                  "userRole": "Employee",
                  "firstName": "Ada",
                  "lastName": "Lovelace",
                  "email": "ada@shift.dk",
                  "employmentStatus": "ACTIVE",
                  "weeklyContractHours": 37.5
                }
                """);

        assertThat(employeeSchema.validate(payload)).isNotEmpty();
    }

    @Test
    void aTombstoneMatchesThePayloadContract() throws Exception {
        JsonNode payload = mapper.readTree("""
                {"entityType": "employee", "entityId": 42, "deletedAt": "2026-11-20T08:15:00Z"}
                """);

        assertThat(tombstoneSchema.validate(payload)).isEmpty();
    }
}
