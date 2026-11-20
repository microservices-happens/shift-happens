package dk.ek.shift_happens.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
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
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Retries and dead-lettering of {@code ai.suggestions}, started against a broker that still has the topology of
 * the previous version (DLQ bound to the DLX with {@code #}, an understaffed event waiting in the queue).
 *
 * <p>A message that cannot be handled is tried three times and then lands in {@code ai.suggestions.dlq} only. The
 * model is called at most once per message, and a model failure is answered by the rule, not dead-lettered.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiDeadLetterIT {

    private static final String EVENTS = "shift-happens.events";
    private static final String DLX = "shift-happens.dlx";
    private static final String QUEUE = "ai.suggestions";
    private static final String DLQ = QUEUE + ".dlq";
    /** A queue of another service whose dead letters are routed by queue name, like leave-service does. */
    private static final String OTHER_DLQ = "it.other.dlq";
    private static final String INPUT = "scheduling.shift.understaffed.v1";
    private static final String OUTPUT = "ai.replacement.suggested.v1";
    private static final String OUT_QUEUE = "it.ai.dead-letter.out";
    /** The stub answers HTTP 500 for prompts with this candidate. */
    private static final long BROKEN_MODEL_CANDIDATE = 5243;
    /** Shifts whose suggestion_log insert fails (a trigger installed by the test). */
    private static final long FAILING_SHIFT = 4242;
    private static final long FAILING_SHIFT_CANDIDATE = 4243;
    private static final UUID WAITING_EVENT = UUID.randomUUID();
    private static final ConcurrentLinkedQueue<String> MODEL_REQUESTS = new ConcurrentLinkedQueue<>();
    private static HttpServer model;

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    static {
        POSTGRES.start();
        RABBIT.start();
        seedLegacyTopology();
    }

    /** Exactly what AiTopology declared before this fix, plus one event that is still waiting. */
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
            channel.queueBind(QUEUE, EVENTS, INPUT);
            channel.queueDeclare(DLQ, true, false, false, null);
            channel.queueBind(DLQ, DLX, "#");
            channel.queueDeclare(OTHER_DLQ, true, false, false, null);
            channel.queueBind(OTHER_DLQ, DLX, "it.other");
            channel.queueBind(OTHER_DLQ, DLX, INPUT);
            channel.queueDeclare(OUT_QUEUE, true, false, false, null);
            channel.queueBind(OUT_QUEUE, EVENTS, OUTPUT);
            channel.basicPublish(EVENTS, INPUT, new AMQP.BasicProperties.Builder()
                            .contentType("application/json").deliveryMode(2).build(),
                    understaffed(WAITING_EVENT, INPUT, 42, 7, 8).getBytes(StandardCharsets.UTF_8));
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
        startModelStub();
        registry.add("app.llm.base-url", () -> "http://127.0.0.1:" + model.getAddress().getPort());
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    private static void startModelStub() {
        try {
            model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ObjectMapper mapper = new ObjectMapper();
            model.createContext("/api/generate", exchange -> {
                try (exchange) {
                    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                    MODEL_REQUESTS.add(request);
                    if (request.contains(String.valueOf(BROKEN_MODEL_CANDIDATE))) {
                        exchange.sendResponseHeaders(500, -1);
                        return;
                    }
                    byte[] response = mapper.writeValueAsBytes(Map.of("response", """
                            {"reason":"Model ranking","rankedCandidates":[{"employeeId":7,"rank":1},{"employeeId":8,"rank":2}]}
                            """));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length);
                    exchange.getResponseBody().write(response);
                }
            });
            model.start();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @AfterAll
    static void stopModelStub() {
        if (model != null) {
            model.stop(0);
        }
    }

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theStaleWildcardBindingIsRemovedAndTheDlqIsBoundToItsQueueNameOnly() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(dlxBindingKeys(DLQ)).containsExactly(QUEUE));
    }

    @Test
    void anEventThatWaitedInTheExistingQueueIsStillHandled() {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id=?",
                        Integer.class, WAITING_EVENT)).isEqualTo(1));
    }

    @Test
    void anUnusableEventIsTriedThreeTimesAndLandsOnlyInTheOwnDlq(CapturedOutput output) {
        UUID poisonId = UUID.randomUUID();
        // Routed by the understaffed key, but the envelope says otherwise: SuggestionService rejects it every time.
        rabbit.send(EVENTS, INPUT, json(understaffed(poisonId, "scheduling.shift.created.v1", 43, 7, 8)));

        Message dead = awaitMessageContaining(DLQ, poisonId.toString());

        assertThat(dead.getMessageProperties().getReceivedRoutingKey())
                .as("dead letters leave with the queue name as routing key").isEqualTo(QUEUE);
        assertThat(count(output.getAll(), "Received scheduling.shift.created.v1 event " + poisonId))
                .as("three delivery attempts, no requeue loop").isEqualTo(3);
        assertThat(drain(OTHER_DLQ)).as("ai's dead letter must not reach another queue's DLQ")
                .noneMatch(body -> body.contains(poisonId.toString()));
    }

    @Test
    void aDatabaseFailureIsRetriedThreeTimesButTheModelIsCalledOnce(CapturedOutput output) {
        jdbc.execute("""
                CREATE OR REPLACE FUNCTION it_fail_suggestion_log() RETURNS trigger AS $$
                BEGIN
                  IF NEW.shift_id = %d THEN RAISE EXCEPTION 'it: suggestion_log is unavailable'; END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(FAILING_SHIFT));
        jdbc.execute("DROP TRIGGER IF EXISTS it_fail_suggestion_log ON suggestion_log");
        jdbc.execute("CREATE TRIGGER it_fail_suggestion_log BEFORE INSERT ON suggestion_log "
                + "FOR EACH ROW EXECUTE FUNCTION it_fail_suggestion_log()");
        UUID eventId = UUID.randomUUID();

        rabbit.send(EVENTS, INPUT, json(understaffed(eventId, INPUT, FAILING_SHIFT, FAILING_SHIFT_CANDIDATE, 4244)));
        awaitMessageContaining(DLQ, eventId.toString());

        assertThat(count(output.getAll(), "Received " + INPUT + " event " + eventId)).as("three delivery attempts").isEqualTo(3);
        assertThat(MODEL_REQUESTS).as("one model call for the message, not one per attempt")
                .filteredOn(request -> request.contains(String.valueOf(FAILING_SHIFT_CANDIDATE))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id=?",
                Integer.class, eventId)).isZero();
    }

    @Test
    void aModelFailureIsAnsweredByTheRuleAndIsNotADeliveryFailure(CapturedOutput output) throws Exception {
        UUID eventId = UUID.randomUUID();
        rabbit.send(EVENTS, INPUT, json(understaffed(eventId, INPUT, 44, BROKEN_MODEL_CANDIDATE, 5244)));

        JsonNode suggestion = awaitSuggestionFor(eventId);

        assertThat(suggestion.path("data").path("source").asText()).isEqualTo("rule");
        assertThat(count(output.getAll(), "Received " + INPUT + " event " + eventId)).as("handled on the first attempt").isEqualTo(1);
        assertThat(drain(DLQ)).noneMatch(body -> body.contains(eventId.toString()));
    }

    @Test
    void foreignDeadLettersDoNotReachTheOwnDlq() {
        String byQueueName = UUID.randomUUID().toString();
        String byEventType = UUID.randomUUID().toString();
        String sentinel = UUID.randomUUID().toString();
        rabbit.send(DLX, "scheduling.command", json(byQueueName));
        rabbit.send(DLX, INPUT, json(byEventType));
        rabbit.send(DLX, QUEUE, json(sentinel));

        List<String> arrived = new ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            arrived.addAll(drain(DLQ));
            return arrived.stream().anyMatch(body -> body.contains(sentinel));
        });
        assertThat(arrived).noneMatch(body -> body.contains(byQueueName) || body.contains(byEventType));
    }

    private static String understaffed(UUID eventId, String eventType, long shiftId, long firstCandidate,
                                       long secondCandidate) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-11-12T10:00:00Z","producer":"scheduling-service",
                 "aggregateId":%d,"aggregateVersion":2,"correlationId":"ai-dead-letter-it",
                 "data":{"shiftId":%d,"shiftName":"Evening","startDatetime":"2026-11-12T16:00:00Z",
                  "endDatetime":"2026-11-12T22:00:00Z","jobRoleId":3,"missingCount":1,"candidates":[
                   {"employeeId":%d,"name":"Candidate One","hoursThisWeek":30,"weeklyContractHours":37},
                   {"employeeId":%d,"name":"Candidate Two","hoursThisWeek":12,"weeklyContractHours":37}]}}
                """.formatted(eventId, eventType, shiftId, shiftId, firstCandidate, secondCandidate);
    }

    private JsonNode awaitSuggestionFor(UUID causationId) throws IOException {
        List<JsonNode> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            Message message;
            while ((message = rabbit.receive(OUT_QUEUE, 300)) != null) {
                seen.add(mapper.readTree(message.getBody()));
            }
            return seen.stream().anyMatch(s -> s.path("causationId").asText().equals(causationId.toString()));
        });
        return seen.stream().filter(s -> s.path("causationId").asText().equals(causationId.toString()))
                .findFirst().orElseThrow();
    }

    private static Message json(String body) {
        return MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON).build();
    }

    private Message awaitMessageContaining(String queue, String marker) {
        List<Message> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            Message message;
            while ((message = rabbit.receive(queue, 300)) != null) {
                seen.add(message);
            }
            return seen.stream().anyMatch(m -> body(m).contains(marker));
        });
        return seen.stream().filter(m -> body(m).contains(marker)).findFirst().orElseThrow();
    }

    private List<String> drain(String queue) {
        List<String> bodies = new ArrayList<>();
        Message message;
        while ((message = rabbit.receive(queue, 300)) != null) {
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
        for (JsonNode binding : mapper.readTree(response.body())) {
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
