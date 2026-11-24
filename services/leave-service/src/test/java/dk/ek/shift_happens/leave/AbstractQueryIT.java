package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.leave.infrastructure.messaging.ProjectionEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Shared harness of the query-role integration tests: a standalone MongoDB with a root user (as in
 * docker-compose.microservices.yml, so NOT MongoDBContainer: that one starts a replica set and would hide a
 * dependence on multi-document transactions) and RabbitMQ. There is no PostgreSQL here: the query role must boot
 * without it. The collections are emptied before every test, so list assertions see only the test's own data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.profiles.active=query"})
abstract class AbstractQueryIT {

    static final String QUEUE = "leave.projection";
    static final String MONGO_USER = "leave";
    static final String MONGO_PASSWORD = "leave_read_test";

    static final GenericContainer<?> MONGO = new GenericContainer<>(DockerImageName.parse("mongo:8.0"))
            .withEnv("MONGO_INITDB_ROOT_USERNAME", MONGO_USER)
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", MONGO_PASSWORD)
            .withExposedPorts(27017)
            // The image starts a throw-away mongod to create the root user, then restarts: two "ready" lines.
            .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 2).withStartupTimeout(Duration.ofMinutes(2)));
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    static {
        MONGO.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.host", MONGO::getHost);
        registry.add("spring.data.mongodb.port", () -> MONGO.getMappedPort(27017));
        registry.add("spring.data.mongodb.database", () -> "leave_read");
        registry.add("spring.data.mongodb.username", () -> MONGO_USER);
        registry.add("spring.data.mongodb.password", () -> MONGO_PASSWORD);
        registry.add("spring.data.mongodb.authentication-database", () -> "admin");
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "shift");
        registry.add("spring.rabbitmq.password", () -> "shift_dev");
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        // The query role validates the token itself (#88), exactly like the command role.
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    protected RabbitTemplate rabbitTemplate;
    @Autowired
    protected MongoTemplate mongo;
    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void emptyTheReadModel() {
        for (String collection : List.of("employees", "leave_types", "leave_requests", "ledger_entries",
                "processed_events")) {
            mongo.remove(new Query(), collection);
        }
        TestJwks.authorizeByDefault(http, "Manager");
    }

    // ---- publishing ----

    /** Publishes to the events exchange with the eventType as routing key, like the producers do. */
    protected ProjectionEnvelope publish(String eventType, long aggregateId, long version, Map<String, Object> data) {
        ProjectionEnvelope envelope = envelope(UUID.randomUUID(), eventType, aggregateId, version, data);
        publish(envelope);
        return envelope;
    }

    protected void publish(ProjectionEnvelope envelope) {
        rabbitTemplate.convertAndSend("shift-happens.events", envelope.eventType(), envelope);
    }

    /** Straight into the queue (default exchange), past the bindings. */
    protected void publishToQueue(ProjectionEnvelope envelope) {
        rabbitTemplate.convertAndSend("", QUEUE, envelope);
    }

    protected ProjectionEnvelope envelope(UUID eventId, String eventType, long aggregateId, long version,
                                          Map<String, Object> data) {
        return new ProjectionEnvelope(eventId, eventType, Instant.now(), "test", aggregateId, version,
                UUID.randomUUID().toString(), null, data == null ? null : objectMapper.valueToTree(data));
    }

    protected ProjectionEnvelope publishAndAwait(String eventType, long aggregateId, long version,
                                                 Map<String, Object> data) {
        ProjectionEnvelope envelope = publish(eventType, aggregateId, version, data);
        awaitProcessed(envelope);
        return envelope;
    }

    protected void awaitProcessed(ProjectionEnvelope envelope) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertThat(isProcessed(envelope)).as("%s processed", envelope.eventType()).isTrue());
    }

    protected boolean isProcessed(ProjectionEnvelope envelope) {
        return mongo.exists(Query.query(Criteria.where("_id").is(envelope.eventId().toString())), "processed_events");
    }

    /**
     * Once a sentinel event has been processed, everything published before it (to the same queue, one consumer) has
     * been handled too, so "nothing happened" assertions are safe.
     */
    protected void drain() {
        awaitProcessed(publish(Events.EMPLOYEE_CREATED, 999_999, 1, Events.employee(999_999, "Sentinel", "Employee")));
    }

    // ---- REST ----

    protected JsonNode getJson(String path) {
        ResponseEntity<JsonNode> response = http.getForEntity(path, JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as("GET %s: %s", path, response.getBody()).isTrue();
        return response.getBody();
    }

    protected ResponseEntity<JsonNode> exchange(HttpMethod method, String path) {
        return http.exchange(path, method, new HttpEntity<>(null), JsonNode.class);
    }

    // ---- RabbitMQ management ----

    protected JsonNode managementGet(String path) throws IOException {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(RABBIT.getHttpUrl() + path))
                .header("Authorization", "Basic " + java.util.Base64.getEncoder()
                        .encodeToString("shift:shift_dev".getBytes(StandardCharsets.UTF_8)))
                .build();
        try {
            return objectMapper.readTree(client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString()).body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected Message awaitDeadLetter(String marker) {
        AtomicReference<Message> dead = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Message message;
            while ((message = rabbitTemplate.receive(QUEUE + ".dlq", 300)) != null) {
                if (new String(message.getBody(), StandardCharsets.UTF_8).contains(marker)) {
                    dead.set(message);
                    return;
                }
            }
            assertThat(dead.get()).as("a dead letter containing %s", marker).isNotNull();
        });
        return dead.get();
    }

    protected static List<String> textsOf(JsonNode array, String field) {
        List<String> texts = new ArrayList<>();
        array.forEach(node -> texts.add(node.path(field).asText()));
        return texts;
    }

    protected static BigDecimal decimal(String text) {
        return new BigDecimal(text);
    }
}
