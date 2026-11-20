package dk.ek.shift_happens.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The audit path on real engines: events in, append-only rows out, Administrator-only search with filters.
 * Duplicate delivery is ignored; the stored envelope is the original one.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditServiceIT {

    private static final String EXCHANGE = "shift-happens.events";
    private static final Path ENVELOPE_SCHEMA = Path.of("../../contracts/events/envelope.schema.json");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    private static JsonSchema envelopeSchema;

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    @BeforeAll
    static void loadSchema() throws Exception {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        try (InputStream in = Files.newInputStream(ENVELOPE_SCHEMA)) {
            envelopeSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(in, config);
        }
    }

    @Test
    void healthIsPublicAndEveryBusinessEventIsAppendedOnce() throws Exception {
        assertThat(http.getForEntity("/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID employeeEvent = UUID.randomUUID();
        UUID swapEvent = UUID.randomUUID();
        String correlation = "audit-it-" + UUID.randomUUID();
        publish("workforce.employee.created.v1", employeeEvent, correlation, 7, 1,
                "{\"employeeId\":7,\"employeeNumber\":\"E-7\",\"userRole\":\"Employee\",\"firstName\":\"A\","
                        + "\"lastName\":\"B\",\"email\":\"a@shift.dk\",\"employmentStatus\":\"ACTIVE\","
                        + "\"jobRoleIds\":[],\"weeklyContractHours\":37}");
        publish("scheduling.swap.approved.v1", swapEvent, correlation, 17, 2,
                "{\"shiftSwapId\":17,\"originalShiftAssignmentId\":88,\"shiftId\":12,\"employeeFromId\":7,"
                        + "\"employeeToId\":8,\"swapStatus\":\"APPROVED\",\"requestDatetime\":\"2026-11-19T10:00:00Z\","
                        + "\"decision\":null}");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entry", Integer.class)).isEqualTo(2));

        // A redelivery of the first event is ignored by the unique event_id.
        publish("workforce.employee.created.v1", employeeEvent, correlation, 7, 1, "{\"employeeId\":7}");
        Thread.sleep(1_500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_entry", Integer.class)).isEqualTo(2);

        JsonNode all = search(TestJwks.administrator(), "");
        assertThat(all).hasSize(2);
        JsonNode swap = "scheduling.swap.approved.v1".equals(all.get(0).path("eventType").asText()) ? all.get(0) : all.get(1);
        assertThat(swap.path("eventId").asText()).isEqualTo(swapEvent.toString());
        assertThat(swap.path("producer").asText()).isEqualTo("scheduling-service");
        assertThat(swap.path("aggregateId").asLong()).isEqualTo(17);
        assertThat(swap.path("correlationId").asText()).isEqualTo(correlation);
        assertThat(envelopeSchema.validate(swap.path("envelope"))).isEmpty();
        assertThat(swap.path("envelope").path("data").path("shiftSwapId").asLong()).isEqualTo(17);

        assertThat(search(TestJwks.administrator(), "?correlationId=" + correlation)).hasSize(2);
        assertThat(search(TestJwks.administrator(), "?eventType=workforce.employee.created.v1")).hasSize(1);
        assertThat(search(TestJwks.administrator(), "?aggregateId=17")).hasSize(1);
        assertThat(search(TestJwks.administrator(), "?from=2099-01-01T00:00:00Z")).isEmpty();
    }

    @Test
    void onlyAnAdministratorMayReadTheAuditLog() {
        assertThat(get("", TestJwks.manager()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("", TestJwks.employee(7)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        var anonymous = http.getForEntity("/v1/auditlogs", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    private JsonNode search(String token, String query) throws Exception {
        var response = get(query, token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
    }

    private org.springframework.http.ResponseEntity<String> get(String query, String token) {
        return http.exchange("/v1/auditlogs" + query, HttpMethod.GET,
                new HttpEntity<>(TestJwks.bearer(token)), String.class);
    }

    private void publish(String eventType, UUID eventId, String correlationId, long aggregateId, long version,
                         String data) {
        String producer = eventType.substring(0, eventType.indexOf('.')) + "-service";
        String envelope = """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","producer":"%s","aggregateId":%d,
                 "aggregateVersion":%d,"correlationId":"%s","causationId":null,"data":%s}
                """.formatted(eventId, eventType, OffsetDateTime.now(), producer, aggregateId, version, correlationId, data);
        rabbit.send(EXCHANGE, eventType, MessageBuilder
                .withBody(envelope.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").build());
    }
}
