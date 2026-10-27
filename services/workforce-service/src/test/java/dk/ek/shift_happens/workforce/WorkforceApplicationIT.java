package dk.ek.shift_happens.workforce;

import dk.ek.shift_happens.workforce.api.dto.CreateEmployeeRequest;
import dk.ek.shift_happens.workforce.api.dto.EmployeeResponse;
import dk.ek.shift_happens.workforce.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
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

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test of the Workforce slice: REST → PostgreSQL → outbox → RabbitMQ, against the real
 * engines (PostgreSQL and RabbitMQ in Testcontainers, no mocks).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkforceApplicationIT {

    private static final String TEST_QUEUE = "it.workforce.employee.events";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

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
    private TestRestTemplate http;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TopicExchange eventsExchange;

    @BeforeEach
    void bindATestQueue() {
        // Durable and non-exclusive: RabbitMQ 4 rejects transient non-exclusive queues.
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with("workforce.employee.#"));
        rabbitTemplate.setReceiveTimeout(500);
        while (rabbitTemplate.receive(TEST_QUEUE) != null) {
            // drain anything left by an earlier test
        }
    }

    @Test
    void healthEndpointIsPublicAndUp() {
        assertThat(http.getForEntity("/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void creatingAnEmployeePublishesTheEventAndMarksTheOutboxRow() {
        CreateEmployeeRequest request = new CreateEmployeeRequest("EMP-1001", "Employee", "Ada", "Lovelace",
                "ada@shift.dk", "+45 50123456", LocalDate.of(2024, 1, 15), "ACTIVE", 1L);

        var response = http.postForEntity("/v1/employees", request, EmployeeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().employeeId()).isNotNull();

        Message message = receiveOneEvent();
        assertThat(message.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo("workforce.employee.created.v1");

        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(body)
                .contains("\"eventType\":\"workforce.employee.created.v1\"")
                .contains("\"producer\":\"workforce-service\"")
                .contains("\"aggregateId\":" + response.getBody().employeeId())
                .contains("\"email\":\"ada@shift.dk\"")
                .contains("\"jobRoleIds\":[]")
                .doesNotContain("salary")
                .doesNotContain("password");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(unpublishedOutboxRows()).isZero());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE email = ?",
                Integer.class, "ada@shift.dk")).isEqualTo(1);
    }

    @Test
    void anEmployeeCanBeReadBack() {
        CreateEmployeeRequest request = new CreateEmployeeRequest("EMP-1002", "Manager", "Grace", "Hopper",
                "grace@shift.dk", null, null, "ACTIVE", null);
        EmployeeResponse created = http.postForEntity("/v1/employees", request, EmployeeResponse.class).getBody();

        var response = http.getForEntity("/v1/employees/" + created.employeeId(), EmployeeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().email()).isEqualTo("grace@shift.dk");
        assertThat(response.getBody().userRole()).isEqualTo("Manager");
    }

    @Test
    void aDuplicateEmployeeNumberIsAConflict() {
        CreateEmployeeRequest request = new CreateEmployeeRequest("EMP-1003", "Employee", "Alan", "Turing",
                "alan@shift.dk", null, null, "ACTIVE", null);
        http.postForEntity("/v1/employees", request, EmployeeResponse.class);

        var duplicate = http.postForEntity("/v1/employees", request, String.class);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    void anUnknownEmployeeIsNotFound() {
        assertThat(http.getForEntity("/v1/employees/999999", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theEnvelopeStillHasTheFieldsFromTheContract() {
        assertThat(EventEnvelope.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("eventId", "eventType", "occurredAt", "producer", "aggregateId",
                        "aggregateVersion", "correlationId", "causationId", "data");
    }

    private int unpublishedOutboxRows() {
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NULL", Integer.class);
        return rows == null ? -1 : rows;
    }

    private Message receiveOneEvent() {
        AtomicReference<Message> received = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Message message = rabbitTemplate.receive(TEST_QUEUE);
            assertThat(message).isNotNull();
            received.set(message);
        });
        return received.get();
    }
}
