package dk.ek.shift_happens.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dk.ek.shift_happens.ai.application.SuggestionService;
import dk.ek.shift_happens.ai.infrastructure.persistence.SuggestionLogRetention;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiServiceApplicationIT {

    private static final String EXCHANGE = "shift-happens.events";
    private static final String INPUT = "scheduling.shift.understaffed.v1";
    private static final String OUTPUT = "ai.replacement.suggested.v1";
    private static final String TEST_QUEUE = "it.ai.suggestions";
    private static final AtomicReference<String> MODEL_ANSWER = new AtomicReference<>();
    private static final AtomicInteger MODEL_CALLS = new AtomicInteger();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    private static HttpServer model;

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
        startModelStub();
        registry.add("app.llm.base-url", () -> "http://127.0.0.1:" + model.getAddress().getPort());
    }

    private static void startModelStub() {
        try {
            model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ObjectMapper mapper = new ObjectMapper();
            model.createContext("/api/generate", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes();
                    MODEL_CALLS.incrementAndGet();
                    byte[] response = mapper.writeValueAsBytes(Map.of("response", MODEL_ANSWER.get()));
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

    @Autowired private TestRestTemplate http;
    @Autowired private RabbitAdmin admin;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SuggestionService suggestions;
    @Autowired private SuggestionLogRetention retention;

    @BeforeEach
    void bindOutputQueue() {
        Queue queue = new Queue(TEST_QUEUE);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(EXCHANGE)).with(OUTPUT));
    }

    @Test
    void healthEndpointIsPublicAndUp() {
        var response = http.getForEntity("/health", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void publishesModelRankingForExactlyTheInputCandidates() throws Exception {
        MODEL_ANSWER.set("""
                {"reason":"Model ranking","rankedCandidates":[{"employeeId":7,"rank":1},{"employeeId":8,"rank":2}]}
                """);
        UUID eventId = UUID.randomUUID();
        JsonNode output = publishAndReceive(input(eventId));
        assertThat(output.path("data").path("source").asText()).isEqualTo("llm");
        assertThat(output.path("data").path("rankedCandidates")).extracting(row -> row.path("employeeId").asLong())
                .containsExactly(7L, 8L);
        UUID outputId = UUID.fromString(output.path("eventId").asText());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_event WHERE event_id=?",
                        Boolean.class, outputId)).isTrue());
    }

    @Test
    void publishesFallbackForMalformedModelOutputAndIgnoresDuplicate() throws Exception {
        MODEL_ANSWER.set("malformed JSON");
        int callsBefore = MODEL_CALLS.get();
        UUID eventId = UUID.randomUUID();
        JsonNode input = input(eventId);
        JsonNode output = publishAndReceive(input);
        assertThat(output.path("data").path("source").asText()).isEqualTo("rule");
        assertThat(output.path("data").path("rankedCandidates")).extracting(row -> row.path("employeeId").asLong())
                .containsExactly(8L, 7L);

        suggestions.handle(input);
        assertThat(MODEL_CALLS.get()).isEqualTo(callsBefore + 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event WHERE event_id=?",
                Integer.class, eventId)).isEqualTo(1);
        jdbc.update("UPDATE suggestion_log SET created_at=? WHERE event_id=?",
                OffsetDateTime.now().minusDays(8), eventId);
        retention.deleteExpiredLogs();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suggestion_log WHERE event_id=?",
                Integer.class, eventId)).isZero();
    }

    private JsonNode input(UUID id) throws IOException {
        return mapper.readTree("""
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","producer":"scheduling-service",
                 "aggregateId":42,"aggregateVersion":2,"correlationId":"ai-it-%s",
                 "data":{"shiftId":42,"shiftName":"Evening","startDatetime":"2026-11-12T16:00:00Z",
                  "endDatetime":"2026-11-12T22:00:00Z","jobRoleId":3,"missingCount":1,"candidates":[
                   {"employeeId":7,"name":"Candidate One","hoursThisWeek":30,"weeklyContractHours":37},
                   {"employeeId":8,"name":"Candidate Two","hoursThisWeek":12,"weeklyContractHours":37}]}}
                """.formatted(id, INPUT, OffsetDateTime.now(), id));
    }

    private JsonNode publishAndReceive(JsonNode input) throws IOException {
        rabbit.send(EXCHANGE, INPUT, MessageBuilder.withBody(mapper.writeValueAsBytes(input))
                .setContentType("application/json").build());
        Message message = rabbit.receive(TEST_QUEUE, 20_000);
        assertThat(message).as("AI suggestion from RabbitMQ").isNotNull();
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo(OUTPUT);
        JsonNode output = mapper.readTree(message.getBody());
        assertThat(output.path("causationId").asText()).isEqualTo(input.path("eventId").asText());
        assertThat(output.path("correlationId").asText()).isEqualTo(input.path("correlationId").asText());
        return output;
    }
}
