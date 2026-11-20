package dk.ek.shift_happens.workforce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dk.ek.shift_happens.workforce.api.dto.CreateEmployeeRequest;
import dk.ek.shift_happens.workforce.api.dto.EmployeeResponse;
import dk.ek.shift_happens.workforce.application.EmployeeTermsRefreshJob;
import dk.ek.shift_happens.workforce.infrastructure.messaging.EventEnvelope;
import dk.ek.shift_happens.workforce.infrastructure.persistence.EmployeeTermsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The local demo data (services/workforce-service/seed/dev-seed.sql, P1.9), run with psql exactly as
 * scripts/seed-dev.sh does, against the real schema from Flyway. It checks that the events the seed
 * puts in the outbox are what EmployeeService would have published: contract-valid envelopes and
 * snapshots, the same jobRoleIds/weeklyContractHours and the same terms-refresh schedule that the Java
 * code computes. No other test reads the seed data.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DevSeedIT {

    private static final String TEST_QUEUE = "it.workforce.dev-seed.events";
    private static final Path SEED = Path.of("seed/dev-seed.sql");
    private static final Path CONTRACTS = Path.of("../../contracts/events");
    private static final int SEEDED_EMPLOYEES = 13;

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
        registry.add("spring.rabbitmq.username", () -> "shift");
        registry.add("spring.rabbitmq.password", () -> "shift_dev");
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EmployeeTermsRepository terms;
    @Autowired
    private EmployeeTermsRefreshJob termsRefreshJob;
    @Autowired
    private TopicExchange eventsExchange;

    @BeforeEach
    void bindATestQueue() {
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with("workforce.employee.#"));
        rabbitTemplate.setReceiveTimeout(500);
    }

    @Test
    void theSeedPublishesTheSnapshotsWorkforceWouldHavePublishedAndRunsOnlyOnce() throws Exception {
        assertThat(seed().getStdout()).contains("dev seed: loaded");

        assertThat(count("department")).isEqualTo(7);
        assertThat(count("job_role")).isEqualTo(8);
        assertThat(count("work_location")).isEqualTo(4);
        assertThat(count("employee")).isEqualTo(SEEDED_EMPLOYEES);

        List<Message> messages = receive(SEEDED_EMPLOYEES);
        JsonSchema envelopeSchema = schema(CONTRACTS.resolve("envelope.schema.json"));
        JsonSchema employeeSchema = schema(CONTRACTS.resolve("payloads/employee.schema.json"));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Set<UUID> eventIds = new HashSet<>();
        Set<Long> employeeIds = new HashSet<>();
        for (Message message : messages) {
            assertThat(message.getMessageProperties().getReceivedRoutingKey())
                    .isEqualTo("workforce.employee.created.v1");
            JsonNode body = objectMapper.readTree(message.getBody());
            assertThat(envelopeSchema.validate(body)).as("envelope").isEmpty();
            assertThat(employeeSchema.validate(body.path("data"))).as("snapshot").isEmpty();

            // Consumers deserialize the same envelope record; this fails on a malformed timestamp or id.
            EventEnvelope envelope = objectMapper.readValue(message.getBody(), EventEnvelope.class);
            assertThat(envelope.aggregateVersion()).isEqualTo(1);
            assertThat(envelope.producer()).isEqualTo("workforce-service");
            assertThat(envelope.eventId().toString()).isEqualTo(message.getMessageProperties().getMessageId());
            eventIds.add(envelope.eventId());

            long employeeId = envelope.aggregateId();
            employeeIds.add(employeeId);
            JsonNode data = body.path("data");
            assertThat(data.path("employeeId").asLong()).isEqualTo(employeeId);
            List<Long> roles = new ArrayList<>();
            data.path("jobRoleIds").forEach(role -> roles.add(role.asLong()));
            assertThat(roles).as("jobRoleIds of %d", employeeId)
                    .isEqualTo(terms.activeJobRoleIds(employeeId, today));
            assertThat(data.path("weeklyContractHours").asInt()).as("hours of %d", employeeId)
                    .isEqualTo(terms.weeklyContractHours(employeeId, today));
            assertThat(data.has("salaryAmount")).isFalse();
        }
        assertThat(eventIds).hasSize(SEEDED_EMPLOYEES);
        assertThat(employeeIds).hasSize(SEEDED_EMPLOYEES);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE published_at IS NULL",
                        Integer.class)).isZero());

        // The schedule is the one EmployeeTermsRepository.reschedule computes for today.
        Map<Long, List<LocalDate>> seededSchedule = refreshSchedule();
        assertThat(seededSchedule).containsKeys(8L, 13L);
        for (long employeeId : employeeIds) {
            terms.reschedule(employeeId, today);
        }
        assertThat(refreshSchedule()).isEqualTo(seededSchedule);

        // Nothing is due today, so the refresh job has nothing to add.
        termsRefreshJob.refreshDueTerms(today);
        assertThat(count("outbox_event")).isEqualTo(SEEDED_EMPLOYEES);

        // A second run is a no-op.
        assertThat(seed().getStdout()).contains("already loaded");
        assertThat(count("employee")).isEqualTo(SEEDED_EMPLOYEES);
        assertThat(count("outbox_event")).isEqualTo(SEEDED_EMPLOYEES);

        // The seeded rows read back through the API, and the sequences moved past the seeded ids.
        assertThat(http.getForEntity("/v1/employees/1", String.class).getBody())
                .contains("\"employeeNumber\":\"DEMO-0001\"");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", UUID.randomUUID().toString());
        var created = http.exchange("/v1/employees", HttpMethod.POST, new HttpEntity<>(new CreateEmployeeRequest(
                "IT-SEED-1", "Employee", "Test", "Person", "test.person@shift-happens.example", null,
                LocalDate.of(2024, 1, 1), "ACTIVE", 1L), headers), EmployeeResponse.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).isNotNull();
        assertThat(created.getBody().employeeId()).isGreaterThan(SEEDED_EMPLOYEES);
    }

    private ExecResult seed() throws IOException, InterruptedException {
        POSTGRES.copyFileToContainer(MountableFile.forHostPath(SEED), "/tmp/dev-seed.sql");
        ExecResult result = POSTGRES.execInContainer("psql", "-v", "ON_ERROR_STOP=1",
                "-U", POSTGRES.getUsername(), "-d", POSTGRES.getDatabaseName(), "-f", "/tmp/dev-seed.sql");
        assertThat(result.getExitCode()).as("psql: %s", result.getStderr()).isZero();
        return result;
    }

    private List<Message> receive(int expected) {
        List<Message> messages = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Message message;
            while ((message = rabbitTemplate.receive(TEST_QUEUE)) != null) {
                messages.add(message);
            }
            assertThat(messages).hasSize(expected);
        });
        return messages;
    }

    private Map<Long, List<LocalDate>> refreshSchedule() {
        Map<Long, List<LocalDate>> schedule = new HashMap<>();
        jdbc.query("SELECT employee_id, due_on, evaluated_on FROM employee_terms_refresh", row -> {
            schedule.put(row.getLong("employee_id"), Arrays.asList(row.getObject("due_on", LocalDate.class),
                    row.getObject("evaluated_on", LocalDate.class)));
        });
        return schedule;
    }

    private int count(String table) {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return rows == null ? -1 : rows;
    }

    private static JsonSchema schema(Path file) throws IOException {
        try (var source = Files.newInputStream(file)) {
            return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(source);
        }
    }
}
