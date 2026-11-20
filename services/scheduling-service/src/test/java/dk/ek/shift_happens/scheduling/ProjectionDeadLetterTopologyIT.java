package dk.ek.shift_happens.scheduling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static dk.ek.shift_happens.scheduling.LegacyBroker.DLX;
import static dk.ek.shift_happens.scheduling.LegacyBroker.EVENTS;
import static dk.ek.shift_happens.scheduling.LegacyBroker.OTHER_DLQ;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Dead-letter topology of {@code scheduling.projection} (query role), started against a broker that still has
 * the topology of the previous version (DLQ bound to the DLX with {@code #}, an event waiting in the queue).
 * Own containers, because the broker must be seeded before the Spring context starts.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("query")
class ProjectionDeadLetterTopologyIT {

    private static final String QUEUE = "scheduling.projection";
    private static final String DLQ = QUEUE + ".dlq";
    private static final String EMPLOYEE_CREATED = "workforce.employee.created.v1";
    private static final String SHIFT_CREATED = "scheduling.shift.created.v1";
    private static final long WAITING_EMPLOYEE = 951;

    static final MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:8.0"));
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    static {
        MONGO.start();
        RABBIT.start();
        LegacyBroker.seed(RABBIT, QUEUE, List.of("scheduling.#", "workforce.employee.#",
                        "ai.replacement.suggested.v1"), SHIFT_CREATED, EMPLOYEE_CREATED,
                envelope(UUID.randomUUID(), EMPLOYEE_CREATED, WAITING_EMPLOYEE, """
                        {"employeeId":%d,"employeeNumber":"EMP-%d","userRole":"Employee","firstName":"Ada",
                         "lastName":"Lovelace","email":"ada@shift.dk","employmentStatus":"ACTIVE",
                         "jobRoleIds":[7],"weeklyContractHours":37}
                        """.formatted(WAITING_EMPLOYEE, WAITING_EMPLOYEE)));
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
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private MongoTemplate mongo;

    @Test
    void theStaleWildcardBindingIsRemovedAndTheDlqIsBoundToItsQueueNameOnly() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(LegacyBroker.dlxBindingKeys(RABBIT, DLQ)).containsExactly(QUEUE));
    }

    @Test
    void anEventThatWaitedInTheExistingQueueIsStillProjected() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(mongo.count(new Query(), "employees")).isEqualTo(1));
    }

    @Test
    void aPoisonMessageIsTriedThreeTimesAndLandsOnlyInTheOwnDlq(CapturedOutput output) {
        UUID poisonId = UUID.randomUUID();
        long shiftId = 952;
        // shiftId is not a number: the payload never matches its schema, so every attempt throws.
        rabbitTemplate.send(EVENTS, SHIFT_CREATED, json(envelope(poisonId, SHIFT_CREATED, shiftId,
                "{\"shiftId\":\"not-a-number\"}")));

        Message dead = LegacyBroker.awaitMessageContaining(rabbitTemplate, DLQ, poisonId.toString());

        assertThat(dead.getMessageProperties().getReceivedRoutingKey())
                .as("dead letters leave with the queue name as routing key").isEqualTo(QUEUE);
        assertThat(LegacyBroker.count(output.getAll(), "Projecting " + SHIFT_CREATED + " for aggregate " + shiftId + " "))
                .as("three delivery attempts, no requeue loop").isEqualTo(3);
        assertThat(LegacyBroker.drain(rabbitTemplate, OTHER_DLQ))
                .as("the projection's dead letter must not reach another queue's DLQ")
                .noneMatch(body -> body.contains(poisonId.toString()));
    }

    @Test
    void foreignDeadLettersDoNotReachTheOwnDlq() {
        String byQueueName = UUID.randomUUID().toString();
        String byEventType = UUID.randomUUID().toString();
        String sentinel = UUID.randomUUID().toString();
        rabbitTemplate.send(DLX, "scheduling.command", json(byQueueName));
        rabbitTemplate.send(DLX, SHIFT_CREATED, json(byEventType));
        rabbitTemplate.send(DLX, QUEUE, json(sentinel));

        assertThat(LegacyBroker.drainUntil(rabbitTemplate, DLQ, sentinel))
                .noneMatch(body -> body.contains(byQueueName) || body.contains(byEventType));
    }

    private static String envelope(UUID eventId, String eventType, long aggregateId, String data) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-10-22T08:00:00Z",
                 "producer":"it","aggregateId":%d,"aggregateVersion":1,
                 "correlationId":"it-dead-letter","causationId":null,"data":%s}
                """.formatted(eventId, eventType, aggregateId, data);
    }

    private static Message json(String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
    }
}
