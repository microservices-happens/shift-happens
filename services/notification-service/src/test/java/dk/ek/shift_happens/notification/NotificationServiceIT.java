package dk.ek.shift_happens.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole consumer path on real engines: workforce/assignment events in, in-app notification through the
 * REST API, email request through the outbox and the exchange, and idempotency/ownership guarantees.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NotificationServiceIT {

    private static final String EXCHANGE = "shift-happens.events";
    private static final String DLX = "shift-happens.dlx";
    private static final String EMAIL_EVENT = "notification.email.requested.v1";
    private static final String EMAIL_TEST_QUEUE = "it.notification.email";

    private static final Path ENVELOPE_SCHEMA = Path.of("../../contracts/events/envelope.schema.json");
    private static final Path EMAIL_SCHEMA = Path.of("../../contracts/events/payloads/email-request.schema.json");

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
        // The outbox relay runs often enough for the test to see the published message quickly.
        registry.add("app.outbox.relay-delay", () -> "200");
    }

    private static JsonSchema envelopeSchema;
    private static JsonSchema emailSchema;

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private RabbitAdmin admin;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;

    @BeforeAll
    static void loadSchemas() throws Exception {
        SchemaValidatorsConfig config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream envelope = Files.newInputStream(ENVELOPE_SCHEMA);
             InputStream email = Files.newInputStream(EMAIL_SCHEMA)) {
            envelopeSchema = factory.getSchema(envelope, config);
            emailSchema = factory.getSchema(email, config);
        }
    }

    @BeforeEach
    void declareEmailTestQueue() {
        Queue queue = new Queue(EMAIL_TEST_QUEUE, true, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(EXCHANGE)).with(EMAIL_EVENT));
        admin.purgeQueue(EMAIL_TEST_QUEUE, false);
    }

    @Test
    void healthIsPublicAndUp() {
        var response = http.getForEntity("/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void anAssignmentBecomesAnInAppNotificationAndAnEmailRequest() throws Exception {
        long employeeId = 7101;
        employee(employeeId, "anna@shift.dk", "Employee", "ACTIVE", 1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT email FROM employee_contact WHERE employee_id=?",
                        String.class, employeeId)).isEqualTo("anna@shift.dk"));

        UUID cause = UUID.randomUUID();
        publish("scheduling.assignment.created.v1", cause, 42, 2, """
                {"shiftAssignmentId":88,"shiftId":42,"employeeId":7101,"assignmentStatus":"ASSIGNED",
                 "shiftStartDatetime":"2026-11-21T16:00:00Z","shiftEndDatetime":"2026-11-21T22:00:00Z"}""");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            JsonNode list = notifications(employeeId);
            assertThat(list).hasSize(1);
            assertThat(list.get(0).path("title").asText()).isEqualTo("New shift assignment");
            assertThat(list.get(0).path("message").asText()).contains("2026-11-21 16:00 UTC");
            assertThat(list.get(0).path("sourceEventId").asText()).isEqualTo(cause.toString());
            assertThat(list.get(0).path("read").asBoolean()).isFalse();
        });

        Message email = rabbit.receive(EMAIL_TEST_QUEUE, 20_000);
        assertThat(email).as("email request on the exchange").isNotNull();
        assertThat(email.getMessageProperties().getReceivedRoutingKey()).isEqualTo(EMAIL_EVENT);
        JsonNode envelope = mapper.readTree(email.getBody());
        assertThat(envelopeSchema.validate(envelope)).isEmpty();
        assertThat(emailSchema.validate(envelope.path("data"))).isEmpty();
        assertThat(envelope.path("data").path("to").asText()).isEqualTo("anna@shift.dk");
        assertThat(envelope.path("data").path("subject").asText()).isEqualTo("New shift assignment");
        assertThat(envelope.path("producer").asText()).isEqualTo("notification-service");
        assertThat(envelope.path("causationId").asText()).isEqualTo(cause.toString());

        // A redelivery of the same eventId must not duplicate the notification or its email.
        publish("scheduling.assignment.created.v1", cause, 42, 2, """
                {"shiftAssignmentId":88,"shiftId":42,"employeeId":7101,"assignmentStatus":"ASSIGNED",
                 "shiftStartDatetime":"2026-11-21T16:00:00Z","shiftEndDatetime":"2026-11-21T22:00:00Z"}""");
        Thread.sleep(2_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE employee_id=?", Integer.class,
                employeeId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isEqualTo(1);
        assertThat(rabbit.receive(EMAIL_TEST_QUEUE, 2_000)).isNull();
    }

    @Test
    void aNotificationIsOnlyVisibleAndAcknowledgeableToItsOwner() throws Exception {
        long owner = 7201;
        employee(owner, "owner@shift.dk", "Employee", "ACTIVE", 1);
        publish("scheduling.assignment.created.v1", UUID.randomUUID(), 43, 2, """
                {"shiftAssignmentId":89,"shiftId":43,"employeeId":7201,"assignmentStatus":"ASSIGNED",
                 "shiftStartDatetime":"2026-11-22T08:00:00Z","shiftEndDatetime":"2026-11-22T12:00:00Z"}""");
        long notificationId = awaitNotificationId(owner);

        var anonymous = http.getForEntity("/v1/notifications", String.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getHeaders().getContentType().toString()).contains("application/problem+json");

        var otherEmployee = patch(notificationId, 7202, "{\"read\":true}");
        assertThat(otherEmployee.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(otherEmployee.getHeaders().getContentType().toString()).contains("application/problem+json");

        var ownerResponse = patch(notificationId, owner, "{\"read\":true}");
        assertThat(ownerResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(mapper.readTree(ownerResponse.getBody()).path("read").asBoolean()).isTrue();

        var unread = http.exchange("/v1/notifications?unreadOnly=true", HttpMethod.GET,
                new HttpEntity<>(TestJwks.bearer(TestJwks.employee(owner))), String.class);
        assertThat(mapper.readTree(unread.getBody())).isEmpty();

        assertThat(patch(999_999, owner, "{\"read\":true}").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theHighestEmployeeSnapshotWinsAndATombstoneStopsFurtherEmail() throws Exception {
        long employeeId = 7301;
        employee(employeeId, "first@shift.dk", "Employee", "ACTIVE", 1);
        employee(employeeId, "newest@shift.dk", "Employee", "ACTIVE", 3);
        employee(employeeId, "older@shift.dk", "Employee", "ACTIVE", 2);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT email FROM employee_contact WHERE employee_id=?",
                        String.class, employeeId)).isEqualTo("newest@shift.dk"));

        publish("workforce.employee.deleted.v1", UUID.randomUUID(), employeeId, 4,
                "{\"entityType\":\"employee\",\"entityId\":7301,\"deletedAt\":\"2026-11-20T09:00:00Z\"}");
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT deleted FROM employee_contact WHERE employee_id=?",
                        Boolean.class, employeeId)).isTrue());

        publish("leave.request.approved.v1", UUID.randomUUID(), 55, 2, """
                {"leaveRequestId":55,"employeeId":7301,"leaveTypeId":1,"leaveTypeName":"Vacation",
                 "startDate":"2026-12-01","endDate":"2026-12-05","requestStatus":"APPROVED",
                 "requestedDatetime":"2026-11-01T10:00:00Z","decision":null}""");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(notifications(employeeId).size()).isEqualTo(1));
        // In-app stays; the email is skipped because the replica keeps the tombstone.
        assertThat(rabbit.receive(EMAIL_TEST_QUEUE, 2_000)).isNull();
    }

    @Test
    void anUnderstaffedShiftAlertsEveryActiveManager() throws Exception {
        employee(7401, "manager1@shift.dk", "Manager", "ACTIVE", 1);
        employee(7402, "manager2@shift.dk", "Manager", "ACTIVE", 1);
        employee(7403, "employee@shift.dk", "Employee", "ACTIVE", 1);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_contact WHERE user_role='Manager'",
                        Integer.class)).isGreaterThanOrEqualTo(2));

        publish("scheduling.shift.understaffed.v1", UUID.randomUUID(), 44, 3, """
                {"shiftId":44,"shiftName":"Evening","startDatetime":"2026-11-25T16:00:00Z",
                 "endDatetime":"2026-11-25T22:00:00Z","jobRoleId":3,"missingCount":2,"candidates":[]}""");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(notifications(7401).get(0).path("title").asText()).isEqualTo("Shift understaffed");
            assertThat(notifications(7402).get(0).path("title").asText()).isEqualTo("Shift understaffed");
        });
        assertThat(notifications(7403)).isEmpty();
    }

    private long awaitNotificationId(long employeeId) throws Exception {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(notifications(employeeId)).isNotEmpty());
        return notifications(employeeId).get(0).path("notificationId").asLong();
    }

    private JsonNode notifications(long employeeId) throws Exception {
        var response = http.exchange("/v1/notifications", HttpMethod.GET,
                new HttpEntity<>(TestJwks.bearer(TestJwks.employee(employeeId))), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readTree(response.getBody());
    }

    private org.springframework.http.ResponseEntity<String> patch(long id, long employeeId, String body) {
        HttpHeaders headers = TestJwks.bearer(TestJwks.employee(employeeId));
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return http.exchange("/v1/notifications/" + id, HttpMethod.PATCH, new HttpEntity<>(body, headers),
                String.class);
    }

    private void employee(long employeeId, String email, String role, String status, long version) throws Exception {
        publish("workforce.employee.created.v1", UUID.randomUUID(), employeeId, version, """
                {"employeeId":%d,"employeeNumber":"E-%d","userRole":"%s","firstName":"Test","lastName":"Person",
                 "email":"%s","employmentStatus":"%s","jobRoleIds":[],"weeklyContractHours":37}
                """.formatted(employeeId, employeeId, role, email, status));
    }

    private void publish(String eventType, UUID eventId, long aggregateId, long version, String data) {
        String producer = switch (eventType.substring(0, eventType.indexOf('.'))) {
            case "workforce" -> "workforce-service";
            case "scheduling" -> "scheduling-service";
            case "leave" -> "leave-service";
            case "ai" -> "ai-service";
            default -> "notification-service";
        };
        String envelope = """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","producer":"%s","aggregateId":%d,
                 "aggregateVersion":%d,"correlationId":"it-%s","causationId":null,"data":%s}
                """.formatted(eventId, eventType, OffsetDateTime.now(), producer, aggregateId, version, eventId, data);
        rabbit.send(EXCHANGE, eventType, MessageBuilder
                .withBody(envelope.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .setContentType("application/json").build());
    }
}
