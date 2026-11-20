package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.application.SchedulingProjectionService;
import dk.ek.shift_happens.scheduling.application.SchedulingQueryService;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared harness of the query-role integration tests: real MongoDB and RabbitMQ, the query profile, and
 * a real JWKS endpoint so the JWT is verified instead of mocked.
 *
 * <p>There is deliberately no Postgres here. If the query role ever needed the write database, these
 * tests would not start, which is the CQRS split made executable.
 *
 * <p>The containers are started once and every subclass reuses the cached context, as in
 * {@code AbstractCommandIT}, so a whole IT run pays for two containers only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("query")
abstract class AbstractQueryIT {

    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    static {
        MONGO.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "shift");
        registry.add("spring.rabbitmq.password", () -> "shift_dev");
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    protected TestRestTemplate http;
    @Autowired
    protected MongoTemplate mongo;
    @Autowired
    protected RabbitTemplate rabbitTemplate;
    @Autowired
    protected TopicExchange eventsExchange;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected SchedulingProjectionService projection;
    @Autowired
    protected SchedulingQueryService queries;

    @BeforeEach
    void clearReadModel() {
        // One database is shared by the tests, so every test starts from empty collections. Removing the
        // documents keeps the indexes the service created at startup.
        for (String collection : List.of("shifts", "assignments", "employees", "swaps", "suggestions",
                "processed_events", "projection_events")) {
            mongo.remove(new Query(), collection);
        }
    }

    /** Publishes an envelope exactly as another service would, so the queue bindings are exercised too. */
    protected void publish(String eventType, long aggregateId, long version, String producer, Object data) {
        rabbitTemplate.convertAndSend(eventsExchange.getName(), eventType, new EventEnvelope(
                UUID.randomUUID(), eventType, Instant.now(), producer, aggregateId, version,
                UUID.randomUUID().toString(), null, data));
    }

    /** Publishes the same envelope twice: the consumer must apply it once. */
    protected EventEnvelope publishTwice(String eventType, long aggregateId, long version, String producer,
                                         Object data) {
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), eventType, Instant.now(), producer,
                aggregateId, version, UUID.randomUUID().toString(), null, data);
        rabbitTemplate.convertAndSend(eventsExchange.getName(), eventType, envelope);
        rabbitTemplate.convertAndSend(eventsExchange.getName(), eventType, envelope);
        return envelope;
    }

    protected long count(String collection) {
        return mongo.count(new Query(), collection);
    }

    /**
     * Read round trips this MongoDB has served so far, counted by the server itself
     * ({@code serverStatus.opcounters}): find commands plus further cursor batches (getMore).
     */
    protected long mongoReads() {
        org.bson.Document counters = mongo.executeCommand(new org.bson.Document("serverStatus", 1))
                .get("opcounters", org.bson.Document.class);
        return ((Number) counters.get("query")).longValue() + ((Number) counters.get("getmore")).longValue();
    }

    protected HttpHeaders authorized(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }
}
