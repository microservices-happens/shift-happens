package dk.ek.shift_happens.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import dk.ek.shift_happens.identity.domain.AccountRepository;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Dead-letter topology of {@code identity.accounts}, started against a broker that still has the topology of
 * the previous version: the work queue with only {@code x-dead-letter-exchange}, {@code identity.accounts.dlq}
 * bound to the DLX with {@code #}, and an event waiting in the queue.
 *
 * <p>The service must start on that broker without a manual step, keep the waiting event, remove the {@code #}
 * binding, and from then on dead-letter only into its own DLQ.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DeadLetterTopologyIT {

    private static final String EVENTS = "shift-happens.events";
    private static final String DLX = "shift-happens.dlx";
    private static final String QUEUE = "identity.accounts";
    private static final String DLQ = QUEUE + ".dlq";
    /** A queue of another service whose dead letters are routed by queue name, like leave-service does. */
    private static final String OTHER_DLQ = "it.other.dlq";
    private static final String CREATED = "workforce.employee.created.v1";
    private static final long WAITING_EMPLOYEE = 701;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    static {
        POSTGRES.start();
        RABBIT.start();
        seedLegacyTopology();
    }

    /** Exactly what RabbitTopology declared before this fix, plus one event that is still waiting. */
    private static void seedLegacyTopology() {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername("shift");
        factory.setPassword("shift_dev");
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(EVENTS, "topic", true);
            channel.exchangeDeclare(DLX, "topic", true);
            channel.queueDeclare(QUEUE, true, false, false, Map.of("x-dead-letter-exchange", DLX));
            channel.queueBind(QUEUE, EVENTS, "workforce.employee.*.v1");
            channel.queueDeclare(DLQ, true, false, false, null);
            channel.queueBind(DLQ, DLX, "#");
            // Dead letters that do not belong to identity: one by queue name, one by original routing key.
            channel.queueDeclare(OTHER_DLQ, true, false, false, null);
            channel.queueBind(OTHER_DLQ, DLX, "it.other");
            channel.queueBind(OTHER_DLQ, DLX, CREATED);
            channel.basicPublish(EVENTS, CREATED, new AMQP.BasicProperties.Builder()
                            .contentType("application/json").deliveryMode(2).build(),
                    snapshot(UUID.randomUUID(), WAITING_EMPLOYEE, "waiting@shift.dk").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("could not seed the legacy topology", e);
        }
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
        registry.add("app.password.pepper", () -> "it-pepper");
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void theStaleWildcardBindingIsRemovedAndTheDlqIsBoundToItsQueueNameOnly() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(dlxBindingKeys(DLQ)).containsExactly(QUEUE));
    }

    @Test
    void anEventThatWaitedInTheExistingQueueIsStillProcessed() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(accounts.findByEmployeeId(WAITING_EMPLOYEE)).isPresent());
    }

    @Test
    void aPoisonMessageIsTriedThreeTimesAndLandsOnlyInTheOwnDlq(CapturedOutput output) {
        UUID poisonId = UUID.randomUUID();
        long employeeId = 702;
        // No employeeId in data: the projection throws on every attempt.
        String poison = """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-10-22T08:00:00Z",
                 "producer":"workforce-service","aggregateId":%d,"aggregateVersion":1,
                 "correlationId":"it-poison","causationId":null,"data":{"firstName":"Nobody"}}
                """.formatted(poisonId, CREATED, employeeId);
        rabbitTemplate.send(EVENTS, CREATED, json(poison));

        Message dead = awaitMessageContaining(DLQ, poisonId.toString());

        assertThat(dead.getMessageProperties().getReceivedRoutingKey())
                .as("dead letters leave with the queue name as routing key").isEqualTo(QUEUE);
        assertThat(count(output.getAll(), "Received " + CREATED + " for employee " + employeeId + " "))
                .as("three delivery attempts, no requeue loop").isEqualTo(3);
        assertThat(drain(OTHER_DLQ)).as("identity's dead letter must not reach another queue's DLQ")
                .noneMatch(body -> body.contains(poisonId.toString()));
    }

    @Test
    void foreignDeadLettersDoNotReachTheOwnDlq() {
        String byQueueName = UUID.randomUUID().toString();
        String byEventType = UUID.randomUUID().toString();
        String sentinel = UUID.randomUUID().toString();
        rabbitTemplate.send(DLX, "scheduling.command", json(byQueueName));
        rabbitTemplate.send(DLX, CREATED, json(byEventType));
        rabbitTemplate.send(DLX, QUEUE, json(sentinel));

        // Collect until the sentinel arrives (published last); the foreign ones must not be among them.
        List<String> arrived = new ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            arrived.addAll(drain(DLQ));
            return arrived.stream().anyMatch(body -> body.contains(sentinel));
        });
        assertThat(arrived).noneMatch(body -> body.contains(byQueueName) || body.contains(byEventType));
    }

    private static String snapshot(UUID eventId, long employeeId, String email) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-10-22T08:00:00Z",
                 "producer":"workforce-service","aggregateId":%d,"aggregateVersion":1,
                 "correlationId":"it-correlation","causationId":null,
                 "data":{"employeeId":%d,"employeeNumber":"EMP-%d","userRole":"Employee",
                         "firstName":"Ada","lastName":"Lovelace","email":"%s","employmentStatus":"ACTIVE",
                         "jobRoleIds":[]}}
                """.formatted(eventId, CREATED, employeeId, employeeId, employeeId, email);
    }

    private static Message json(String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
    }

    private Message awaitMessageContaining(String queue, String marker) {
        List<Message> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            Message message;
            while ((message = rabbitTemplate.receive(queue, 300)) != null) {
                seen.add(message);
            }
            return seen.stream().anyMatch(m -> body(m).contains(marker));
        });
        return seen.stream().filter(m -> body(m).contains(marker)).findFirst().orElseThrow();
    }

    private List<String> drain(String queue) {
        List<String> bodies = new ArrayList<>();
        Message message;
        while ((message = rabbitTemplate.receive(queue, 300)) != null) {
            bodies.add(body(message));
        }
        return bodies;
    }

    private static String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    /** Routing keys of every binding from the DLX to the queue, read from the management API. */
    private List<String> dlxBindingKeys(String queue) throws Exception {
        URI uri = URI.create(RABBIT.getHttpUrl() + "/api/bindings/%2F/e/" + DLX + "/q/" + queue);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("shift:shift_dev".getBytes(StandardCharsets.UTF_8)))
                .GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> keys = new ArrayList<>();
        for (JsonNode binding : objectMapper.readTree(response.body())) {
            keys.add(binding.path("routing_key").asText());
        }
        return keys;
    }

    private static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
