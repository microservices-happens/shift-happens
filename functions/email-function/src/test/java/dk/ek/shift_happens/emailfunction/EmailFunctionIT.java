package dk.ek.shift_happens.emailfunction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The serverless email path on real engines: a message on email.send goes over real SMTP to Mailpit (the
 * local mail server from Compose), the same eventId is not sent twice, and an unusable payload ends in the
 * dead-letter queue after three attempts instead of being requeued forever.
 */
@Testcontainers
@SpringBootTest
class EmailFunctionIT {

    private static final String EXCHANGE = "shift-happens.events";
    private static final String EMAIL_EVENT = "notification.email.requested.v1";

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    @Container
    static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:latest"))
            .withExposedPorts(1025, 8025)
            .waitingFor(Wait.forHttp("/api/v1/messages").forPort(8025).forStatusCode(200));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(1025));
        registry.add("spring.mail.properties.mail.smtp.auth", () -> "false");
        registry.add("app.mail.from", () -> "no-reply@shift-happens.local");
        registry.add("app.function.exit-when-idle", () -> "false");
    }

    @Autowired
    private RabbitTemplate rabbit;
    @Autowired
    private ObjectMapper mapper;

    @org.junit.jupiter.api.BeforeEach
    void clearMailbox() throws Exception {
        HttpRequest delete = HttpRequest.newBuilder(URI.create("http://" + MAILPIT.getHost() + ":"
                + MAILPIT.getMappedPort(8025) + "/api/v1/messages")).DELETE().build();
        HTTP.send(delete, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anEmailRequestIsSentOverSmtpExactlyOncePerEventId() throws Exception {
        UUID eventId = UUID.randomUUID();
        publish(request(eventId, 501, "anna@shift.dk", "New shift assignment", "Evening shift, 16-22"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(messages()).hasSize(1));
        JsonNode email = messages().get(0);
        assertThat(email.path("Subject").asText()).isEqualTo("New shift assignment");
        assertThat(email.path("To").get(0).path("Address").asText()).isEqualTo("anna@shift.dk");
        assertThat(email.path("From").path("Address").asText()).isEqualTo("no-reply@shift-happens.local");

        // The same eventId again (a redelivery) must not send a second email.
        publish(request(eventId, 501, "anna@shift.dk", "New shift assignment", "Evening shift, 16-22"));
        Thread.sleep(1_500);
        assertThat(messages()).hasSize(1);
    }

    @Test
    void anUnusablePayloadIsDeadLetteredInsteadOfRequeuedForever() throws Exception {
        publish("""
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","producer":"notification-service",
                 "aggregateId":77,"aggregateVersion":1,"correlationId":"it-dlq","causationId":null,
                 "data":{"notificationId":77,"subject":"Missing recipient","body":"no address"}}
                """.formatted(UUID.randomUUID(), EMAIL_EVENT, OffsetDateTime.now()));

        Message dead = rabbit.receive("email.send.dlq", 25_000);
        assertThat(dead).as("message in the dead-letter queue").isNotNull();
        assertThat(mapper.readTree(dead.getBody()).path("eventType").asText()).isEqualTo(EMAIL_EVENT);
        assertThat(messages()).isEmpty();
    }

    private void publish(String envelope) {
        rabbit.send(EXCHANGE, EMAIL_EVENT, MessageBuilder
                .withBody(envelope.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json").build());
    }

    private static String request(UUID eventId, long notificationId, String to, String subject, String body) {
        return """
                {"eventId":"%s","eventType":"notification.email.requested.v1","occurredAt":"%s",
                 "producer":"notification-service","aggregateId":%d,"aggregateVersion":1,
                 "correlationId":"it-email","causationId":null,
                 "data":{"notificationId":%d,"to":"%s","subject":"%s","body":"%s"}}
                """.formatted(eventId, OffsetDateTime.now(), notificationId, notificationId, to, subject, body);
    }

    /** The messages Mailpit accepted, newest first (its REST API, the same view as http://localhost:8025). */
    private JsonNode messages() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://" + MAILPIT.getHost() + ":"
                + MAILPIT.getMappedPort(8025) + "/api/v1/messages")).GET().build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return mapper.readTree(response.body()).path("messages");
    }
}
