package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Shared harness of the command-role integration tests: real PostgreSQL and RabbitMQ, one Spring
 * context, one broker and one test queue.
 *
 * <p>The containers are started once in a static initializer instead of being managed per class, so
 * every subclass reuses the same cached Spring context (which points at those ports) and the whole IT
 * run pays for two containers only.
 *
 * <p>Assertions in the subclasses must be scoped to the rows they created: the tests share a database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.outbox.relay-delay=200", "spring.datasource.hikari.connection-timeout=2000"})
abstract class AbstractCommandIT {

    static final String TEST_QUEUE = "it.scheduling.events";

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
    protected TestRestTemplate http;
    @Autowired
    protected RabbitAdmin rabbitAdmin;
    @Autowired
    protected RabbitTemplate rabbitTemplate;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected TopicExchange eventsExchange;
    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void bindATestQueue() {
        // Durable and non-exclusive: RabbitMQ 4 rejects transient non-exclusive queues.
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with("#"));
        while (rabbitTemplate.receive(TEST_QUEUE, 200) != null) {
            // drain anything left by an earlier test
        }
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

    protected <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        return http.postForEntity(path, new HttpEntity<>(body, headersFor(UUID.randomUUID().toString(), null)), type);
    }

    protected <T> ResponseEntity<T> put(String path, Object body, Class<T> type) {
        return http.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headersFor(null, null)), type);
    }

    protected ResponseEntity<String> deleteRaw(String path) {
        return http.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headersFor(null, null)), String.class);
    }

    /** Publishes an envelope exactly as another service would, so the contract shapes are exercised. */
    protected void publish(EventEnvelope envelope) {
        rabbitTemplate.convertAndSend(eventsExchange.getName(), envelope.eventType(), envelope);
    }

    /**
     * Waits for one event of that type about that aggregate and discards events belonging to another
     * test: the queue is shared and the outbox relay is asynchronous.
     */
    protected JsonNode receiveEvent(String eventType, long aggregateId) {
        AtomicReference<Message> match = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message message = rabbitTemplate.receive(TEST_QUEUE, 300);
            while (message != null) {
                JsonNode candidate = objectMapper.readTree(message.getBody());
                if (eventType.equals(candidate.path("eventType").asText())
                        && candidate.path("aggregateId").asLong() == aggregateId) {
                    match.set(message);
                    return;
                }
                message = rabbitTemplate.receive(TEST_QUEUE, 300);
            }
            assertThat(match.get()).as("a %s event for %s", eventType, aggregateId).isNotNull();
        });
        Message message = match.get();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo(eventType);
        try {
            return objectMapper.readTree(message.getBody());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot parse the event body", e);
        }
    }

    protected void awaitCount(String sql, int expected, Object... args) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(sql, Integer.class, args)).isEqualTo(expected));
    }
}
