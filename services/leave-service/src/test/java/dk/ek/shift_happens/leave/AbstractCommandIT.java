package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.BindingBuilder;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared harness of the command-role integration tests: real PostgreSQL and RabbitMQ (no H2, no
 * embedded broker), one Spring context.
 *
 * <p>The containers start once in a static initializer so every subclass reuses the same cached
 * Spring context and the IT run pays for two containers only. Assertions in subclasses must be
 * scoped to the rows they created, because the tests share a database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.outbox.relay-delay=200", "spring.datasource.hikari.connection-timeout=2000"})
abstract class AbstractCommandIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

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
    protected RabbitAdmin rabbitAdmin;
    @Autowired
    protected RabbitTemplate rabbitTemplate;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected TopicExchange eventsExchange;
    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected ObjectMapper objectMapper;

    /** Declares a throwaway queue bound to the events exchange for one routing key. */
    protected String bindTestQueue(String queueName, String routingKey) {
        // Durable and non-exclusive: RabbitMQ 4 rejects transient non-exclusive queues.
        Queue queue = new Queue(queueName, true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with(routingKey));
        return queueName;
    }

    protected HttpHeaders headersFor(String idempotencyKey, String correlationId) {
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

    /** POST with a fresh Idempotency-Key. */
    protected <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        return http.postForEntity(path, new HttpEntity<>(body, headersFor(UUID.randomUUID().toString(), null)), type);
    }

    protected <T> ResponseEntity<T> put(String path, Object body, Class<T> type) {
        return http.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headersFor(null, null)), type);
    }

    protected <T> ResponseEntity<T> patch(String path, Object body, Class<T> type) {
        return http.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headersFor(null, null)), type);
    }

    protected ResponseEntity<String> delete(String path) {
        return http.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headersFor(null, null)), String.class);
    }

    /** Seeds the employee replica directly, as the workforce consumer would have. */
    protected void seedEmployee(long employeeId, String employmentStatus, boolean tombstoned) {
        jdbc.update("INSERT INTO employee_replica (employee_id, first_name, last_name, employment_status, "
                        + "aggregate_version, deleted_at) VALUES (?, 'Ada', 'Lovelace', ?, 1, "
                        + (tombstoned ? "now()" : "NULL") + ") ON CONFLICT (employee_id) DO NOTHING",
                employeeId, employmentStatus);
    }

    protected void seedEmployee(long employeeId) {
        seedEmployee(employeeId, "ACTIVE", false);
    }

    protected long createLeaveType(String name) {
        ResponseEntity<Map> response = post("/v1/leavetypes", Map.of("leaveTypeName", name), Map.class);
        return ((Number) response.getBody().get("leaveTypeId")).longValue();
    }

    /**
     * All outbox rows of one type about one leave request, oldest first, as parsed envelopes. The data filter
     * keeps synthetic events of other tests (OutboxRelayIT) with the same aggregate id out.
     */
    protected List<JsonNode> outboxEvents(String eventType, long aggregateId) {
        return jdbc.queryForList("SELECT payload FROM outbox_event WHERE event_type = ? AND aggregate_id = ? "
                        + "AND payload::jsonb -> 'data' ->> 'leaveRequestId' = ?::text "
                        + "ORDER BY occurred_at, event_id", String.class, eventType, aggregateId, aggregateId).stream()
                .map(payload -> {
                    try {
                        return objectMapper.readTree(payload);
                    } catch (java.io.IOException e) {
                        throw new IllegalStateException(e);
                    }
                }).toList();
    }
}
