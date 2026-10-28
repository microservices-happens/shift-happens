package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftRequest;
import dk.ek.shift_happens.scheduling.api.dto.RequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftRequest;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test of the scheduling command slice: REST -> PostgreSQL -> outbox -> RabbitMQ, against
 * the real engines (PostgreSQL and RabbitMQ in Testcontainers, no mocks).
 *
 * <p>Assertions are always scoped to the shift the test just created: the tests share one database and
 * one broker, so a global count would depend on execution order.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.outbox.relay-delay=200", "spring.datasource.hikari.connection-timeout=2000"})
class ShiftCommandIT {

    private static final String TEST_QUEUE = "it.scheduling.shift.events";
    private static final String SHIFT_CREATED = "scheduling.shift.created.v1";
    private static final String SHIFT_UPDATED = "scheduling.shift.updated.v1";
    private static final String SHIFT_DELETED = "scheduling.shift.deleted.v1";
    private static final Instant START = Instant.parse("2026-11-02T06:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T14:00:00Z");

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
    private TopicExchange eventsExchange;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void bindATestQueue() {
        // Durable and non-exclusive: RabbitMQ 4 rejects transient non-exclusive queues.
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with("scheduling.shift.#"));
        while (rabbitTemplate.receive(TEST_QUEUE, 200) != null) {
            // drain anything left by an earlier test
        }
    }

    @Test
    void healthEndpointIsPublicAndUp() {
        assertThat(http.getForEntity("/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void creatingAShiftStoresItsJobRolesAndPublishesTheEvent() throws Exception {
        ResponseEntity<ShiftResponse> response = createMorningShift();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long shiftId = Objects.requireNonNull(response.getBody()).shiftId();
        assertThat(shiftId).isNotNull();
        assertThat(response.getBody().shiftStatus()).isEqualTo("PLANNED");

        assertThat(jdbc.queryForObject("SELECT shift_name FROM shift WHERE shift_id = ?", String.class, shiftId))
                .isEqualTo("Morning");
        assertThat(jdbc.queryForObject("SELECT department_id FROM shift WHERE shift_id = ?", Long.class, shiftId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shift_required_job_role WHERE shift_id = ?", Integer.class, shiftId))
                .isEqualTo(2);

        JsonNode event = receiveEvent(SHIFT_CREATED, shiftId);
        assertThat(event.path("producer").asText()).isEqualTo("scheduling-service");
        assertThat(event.path("aggregateVersion").asLong()).isEqualTo(1);
        assertThat(event.path("occurredAt").asText()).isNotBlank();
        assertThat(event.path("correlationId").asText()).isNotBlank();

        JsonNode data = event.path("data");
        assertThat(data.path("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(data.path("departmentId").asLong()).isEqualTo(2);
        assertThat(data.path("workLocationId").asLong()).isEqualTo(3);
        assertThat(data.path("shiftStatus").asText()).isEqualTo("PLANNED");
        assertThat(data.path("startDatetime").asText()).isEqualTo("2026-11-02T06:00:00Z");
        assertThat(data.path("endDatetime").asText()).isEqualTo("2026-11-02T14:00:00Z");
        assertThat(data.path("requiredJobRoles").size()).isEqualTo(2);
        assertThat(data.path("requiredJobRoles").get(0).path("jobRoleId").asLong()).isEqualTo(7);
        assertThat(data.path("requiredJobRoles").get(0).path("requiredEmployeeCount").asInt()).isEqualTo(2);

        // The relay published the row that was written in the same transaction as the shift.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL AND aggregate_id = ?",
                Integer.class, shiftId)).isEqualTo(1));
    }

    @Test
    void theCorrelationIdHeaderIsCopiedIntoTheEvent() throws Exception {
        String correlationId = UUID.randomUUID().toString();

        ResponseEntity<ShiftResponse> response = http.exchange("/v1/shifts", HttpMethod.POST,
                new HttpEntity<>(morningShift(), headersFor(UUID.randomUUID().toString(), correlationId)),
                ShiftResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long shiftId = Objects.requireNonNull(response.getBody()).shiftId();
        assertThat(receiveEvent(SHIFT_CREATED, shiftId).path("correlationId").asText()).isEqualTo(correlationId);
    }

    @Test
    void aShiftMustNotEndBeforeItStarts() throws Exception {
        ResponseEntity<String> response = postRaw(new CreateShiftRequest(2, 3, "Broken", END, START, "PLANNED",
                List.of(new RequiredJobRoleRequest(7, 1))), UUID.randomUUID().toString());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        JsonNode problem = objectMapper.readTree(response.getBody());
        assertThat(problem.path("title").asText()).isEqualTo("Invalid shift");
        assertThat(problem.path("detail").asText()).contains("endDatetime must be after startDatetime");
    }

    @Test
    void anIdempotencyKeyIsRequired() {
        assertThat(postRaw(morningShift(), null).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anIdempotencyKeyMustBeAUuid() {
        assertThat(postRaw(morningShift(), "not-a-uuid").getBody()).contains("must be a UUID");
    }

    @Test
    void updatingAShiftPublishesTheNewSnapshot() throws Exception {
        Long shiftId = createMorningShift().getBody().shiftId();

        ResponseEntity<ShiftResponse> response = putShift(shiftId,
                new UpdateShiftRequest(2, 3, "Evening", START, END, "PUBLISHED", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(Objects.requireNonNull(response.getBody()).shiftName()).isEqualTo("Evening");
        assertThat(response.getBody().shiftStatus()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT shift_name FROM shift WHERE shift_id = ?", String.class, shiftId))
                .isEqualTo("Evening");
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM shift WHERE shift_id = ?", Long.class, shiftId))
                .isEqualTo(2L);

        JsonNode event = receiveEvent(SHIFT_UPDATED, shiftId);
        assertThat(event.path("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(event.path("data").path("shiftName").asText()).isEqualTo("Evening");
        assertThat(event.path("data").path("shiftStatus").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void anImpossibleStatusChangeIsAConflict() throws Exception {
        Long shiftId = createMorningShift().getBody().shiftId();
        assertThat(putShift(shiftId, new UpdateShiftRequest(2, 3, "Morning", START, END, "CANCELLED", null))
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> response = putRaw(shiftId,
                new UpdateShiftRequest(2, 3, "Morning", START, END, "PUBLISHED", null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        JsonNode problem = objectMapper.readTree(response.getBody());
        assertThat(problem.path("title").asText()).isEqualTo("Shift cannot be changed");
        assertThat(problem.path("detail").asText()).contains("from CANCELLED to PUBLISHED");
    }

    @Test
    void deletingAShiftPublishesATombstoneAndKeepsTheRow() throws Exception {
        Long shiftId = createMorningShift().getBody().shiftId();

        assertThat(deleteShift(shiftId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(jdbc.queryForObject("SELECT deleted FROM shift WHERE shift_id = ?", Boolean.class, shiftId))
                .isTrue();
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM shift WHERE shift_id = ?", Long.class, shiftId))
                .isEqualTo(2L);

        JsonNode event = receiveEvent(SHIFT_DELETED, shiftId);
        assertThat(event.path("aggregateVersion").asLong()).isEqualTo(2);
        JsonNode data = event.path("data");
        assertThat(data.path("entityType").asText()).isEqualTo("shift");
        assertThat(data.path("entityId").asLong()).isEqualTo(shiftId);
        assertThat(data.path("deletedAt").asText()).isNotBlank();
    }

    @Test
    void aDeletedShiftIsGoneForUpdatesAndDeletes() {
        Long shiftId = createMorningShift().getBody().shiftId();
        deleteShift(shiftId);

        assertThat(putRaw(shiftId, new UpdateShiftRequest(2, 3, "Morning", START, END, "PUBLISHED", null))
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(deleteShift(shiftId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anUnknownShiftIsNotFound() throws Exception {
        ResponseEntity<String> updateResponse = putRaw(9_999_999L,
                new UpdateShiftRequest(2, 3, "Ghost", START, END, "PLANNED", null));

        assertThat(updateResponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(updateResponse.getBody()).path("title").asText())
                .isEqualTo("Shift not found");
        assertThat(deleteShift(9_999_999L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<ShiftResponse> createMorningShift() {
        return http.postForEntity("/v1/shifts",
                new HttpEntity<>(morningShift(), headersFor(UUID.randomUUID().toString(), null)),
                ShiftResponse.class);
    }

    private ResponseEntity<ShiftResponse> putShift(Long shiftId, UpdateShiftRequest request) {
        return http.exchange("/v1/shifts/" + shiftId, HttpMethod.PUT,
                new HttpEntity<>(request, headersFor(null, null)), ShiftResponse.class);
    }

    private ResponseEntity<String> putRaw(Long shiftId, UpdateShiftRequest request) {
        return http.exchange("/v1/shifts/" + shiftId, HttpMethod.PUT,
                new HttpEntity<>(request, headersFor(null, null)), String.class);
    }

    private ResponseEntity<Void> deleteShift(Long shiftId) {
        return http.exchange("/v1/shifts/" + shiftId, HttpMethod.DELETE,
                new HttpEntity<>(headersFor(null, null)), Void.class);
    }

    private ResponseEntity<String> postRaw(CreateShiftRequest request, String idempotencyKey) {
        return http.postForEntity("/v1/shifts", new HttpEntity<>(request, headersFor(idempotencyKey, null)),
                String.class);
    }

    private static CreateShiftRequest morningShift() {
        return new CreateShiftRequest(2, 3, "Morning", START, END, "PLANNED",
                List.of(new RequiredJobRoleRequest(7, 2), new RequiredJobRoleRequest(9, 1)));
    }

    private HttpHeaders headersFor(String idempotencyKey, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.add("Idempotency-Key", idempotencyKey);
        }
        if (correlationId != null) {
            headers.add("X-Correlation-Id", correlationId);
        }
        return headers;
    }

    /**
     * Waits for the event of this shift and event type, and discards events belonging to another test:
     * the queue is shared and the relay is asynchronous.
     */
    private JsonNode receiveEvent(String eventType, long shiftId) throws Exception {
        AtomicReference<Message> match = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message message = rabbitTemplate.receive(TEST_QUEUE, 300);
            while (message != null) {
                JsonNode candidate = objectMapper.readTree(message.getBody());
                if (eventType.equals(candidate.path("eventType").asText())
                        && candidate.path("aggregateId").asLong() == shiftId) {
                    match.set(message);
                    return;
                }
                message = rabbitTemplate.receive(TEST_QUEUE, 300);
            }
            assertThat(match.get()).as("a %s event for shift %s", eventType, shiftId).isNotNull();
        });
        Message message = match.get();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo(eventType);
        return objectMapper.readTree(message.getBody());
    }
}
