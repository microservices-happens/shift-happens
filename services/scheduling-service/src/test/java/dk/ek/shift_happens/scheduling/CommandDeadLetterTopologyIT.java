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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
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
 * Dead-letter topology of {@code scheduling.command}, started against a broker that still has the topology of
 * the previous version (DLQ bound to the DLX with {@code #}, an event waiting in the queue). Own containers,
 * because the broker must be seeded before the Spring context starts.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.outbox.relay-delay=200", "spring.datasource.hikari.connection-timeout=2000"})
class CommandDeadLetterTopologyIT {

    private static final String QUEUE = "scheduling.command";
    private static final String DLQ = QUEUE + ".dlq";
    private static final String CREATED = "workforce.employee.created.v1";
    private static final long WAITING_EMPLOYEE = 901;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    static {
        POSTGRES.start();
        RABBIT.start();
        LegacyBroker.seed(RABBIT, QUEUE, List.of("workforce.employee.*.v1", "leave.request.approval-started.v1",
                        "leave.request.approved.v1", "leave.request.cancelled.v1"), CREATED, CREATED,
                employeeEvent(UUID.randomUUID(), WAITING_EMPLOYEE, true));
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
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theStaleWildcardBindingIsRemovedAndTheDlqIsBoundToItsQueueNameOnly() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(LegacyBroker.dlxBindingKeys(RABBIT, DLQ)).containsExactly(QUEUE));
    }

    @Test
    void anEventThatWaitedInTheExistingQueueIsStillProcessed() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM employee_replica WHERE employee_id = ?",
                        Integer.class, WAITING_EMPLOYEE)).isEqualTo(1));
    }

    @Test
    void aPoisonMessageIsTriedThreeTimesAndLandsOnlyInTheOwnDlq(CapturedOutput output) {
        UUID poisonId = UUID.randomUUID();
        long employeeId = 902;
        // No employeeId in data: the replica throws on every attempt.
        rabbitTemplate.send(EVENTS, CREATED, json(employeeEvent(poisonId, employeeId, false)));

        Message dead = LegacyBroker.awaitMessageContaining(rabbitTemplate, DLQ, poisonId.toString());

        assertThat(dead.getMessageProperties().getReceivedRoutingKey())
                .as("dead letters leave with the queue name as routing key").isEqualTo(QUEUE);
        assertThat(LegacyBroker.count(output.getAll(), "Received " + CREATED + " for aggregate " + employeeId + " "))
                .as("three delivery attempts, no requeue loop").isEqualTo(3);
        assertThat(LegacyBroker.drain(rabbitTemplate, OTHER_DLQ))
                .as("scheduling's dead letter must not reach another queue's DLQ")
                .noneMatch(body -> body.contains(poisonId.toString()));
    }

    @Test
    void foreignDeadLettersDoNotReachTheOwnDlq() {
        String byQueueName = UUID.randomUUID().toString();
        String byEventType = UUID.randomUUID().toString();
        String sentinel = UUID.randomUUID().toString();
        rabbitTemplate.send(DLX, "identity.accounts", json(byQueueName));
        rabbitTemplate.send(DLX, CREATED, json(byEventType));
        rabbitTemplate.send(DLX, QUEUE, json(sentinel));

        assertThat(LegacyBroker.drainUntil(rabbitTemplate, DLQ, sentinel))
                .noneMatch(body -> body.contains(byQueueName) || body.contains(byEventType));
    }

    private static String employeeEvent(UUID eventId, long employeeId, boolean withEmployeeId) {
        String data = withEmployeeId
                ? """
                  {"employeeId":%d,"employeeNumber":"EMP-%d","userRole":"Employee","firstName":"Grace",
                   "lastName":"Hopper","email":"grace%d@shift.dk","employmentStatus":"ACTIVE","jobRoleIds":[]}
                  """.formatted(employeeId, employeeId, employeeId)
                : "{\"firstName\":\"Nobody\"}";
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-10-22T08:00:00Z",
                 "producer":"workforce-service","aggregateId":%d,"aggregateVersion":1,
                 "correlationId":"it-dead-letter","causationId":null,"data":%s}
                """.formatted(eventId, CREATED, employeeId, data);
    }

    private static Message json(String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
    }
}
