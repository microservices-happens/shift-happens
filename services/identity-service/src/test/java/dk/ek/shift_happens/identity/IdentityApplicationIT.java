package dk.ek.shift_happens.identity;

import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test of the Identity consumer: RabbitMQ → listener → account table, against PostgreSQL
 * and RabbitMQ in Testcontainers. It also covers the three properties the consumer must have:
 * idempotency, commutativity (highest version wins) and the tombstone rule.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdentityApplicationIT {

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
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private JdbcTemplate jdbc;
    @Value("${app.events.exchange}")
    private String exchange;

    private void publishSnapshot(String eventType, UUID eventId, long employeeId, long version,
                                 String email, String status) {
        String body = """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-10-22T08:00:00Z",
                 "producer":"workforce-service","aggregateId":%d,"aggregateVersion":%d,
                 "correlationId":"it-correlation","causationId":null,
                 "data":{"employeeId":%d,"employeeNumber":"EMP-%d","userRole":"Employee",
                         "firstName":"Ada","lastName":"Lovelace","email":"%s","employmentStatus":"%s",
                         "jobRoleIds":[]}}
                """.formatted(eventId, eventType, employeeId, version, employeeId, employeeId, email, status);

        rabbitTemplate.send(exchange, eventType, MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build());
    }

    private void publishTombstone(UUID eventId, long employeeId, long version) {
        String body = """
                {"eventId":"%s","eventType":"workforce.employee.deleted.v1","occurredAt":"2026-10-22T09:00:00Z",
                 "producer":"workforce-service","aggregateId":%d,"aggregateVersion":%d,
                 "correlationId":"it-correlation","causationId":null,
                 "data":{"entityType":"employee","entityId":%d,"deletedAt":"2026-10-22T09:00:00Z"}}
                """.formatted(eventId, employeeId, version, employeeId);

        rabbitTemplate.send(exchange, "workforce.employee.deleted.v1", MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build());
    }

    private Optional<Account> awaitAccount(long employeeId) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(accounts.findByEmployeeId(employeeId)).isPresent());
        return accounts.findByEmployeeId(employeeId);
    }

    private int accountRows(long employeeId) {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM account WHERE employee_id = ?",
                Integer.class, employeeId);
        return rows == null ? -1 : rows;
    }

    @Test
    void healthEndpointIsPublicAndUp() {
        assertThat(http.getForEntity("/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aCreatedEventCreatesTheAccount() {
        publishSnapshot("workforce.employee.created.v1", UUID.randomUUID(), 101, 1, "ada@shift.dk", "ACTIVE");

        Account account = awaitAccount(101).orElseThrow();

        assertThat(account.email()).isEqualTo("ada@shift.dk");
        assertThat(account.role()).isEqualTo("Employee");
        assertThat(account.active()).isTrue();
        assertThat(account.aggregateVersion()).isEqualTo(1);
    }

    @Test
    void aRedeliveredEventHasNoSecondEffect() {
        UUID eventId = UUID.randomUUID();
        publishSnapshot("workforce.employee.created.v1", eventId, 102, 1, "dup@shift.dk", "ACTIVE");
        awaitAccount(102);

        // Same eventId again: the consumer must ignore it.
        publishSnapshot("workforce.employee.created.v1", eventId, 102, 1, "dup@shift.dk", "ACTIVE");

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(accountRows(102)).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, eventId)).isEqualTo(1);
    }

    @Test
    void aTombstoneDisablesTheAccountAndKeepsIt() {
        publishSnapshot("workforce.employee.created.v1", UUID.randomUUID(), 103, 1, "bye@shift.dk", "ACTIVE");
        awaitAccount(103);

        publishTombstone(UUID.randomUUID(), 103, 2);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(accounts.findByEmployeeId(103).orElseThrow().active()).isFalse());
        assertThat(accountRows(103)).isEqualTo(1);
    }

    @Test
    void anOlderVersionDoesNotOverwriteANewerOne() {
        publishSnapshot("workforce.employee.updated.v1", UUID.randomUUID(), 104, 2, "newer@shift.dk", "ACTIVE");
        awaitAccount(104);

        // Arrives late, with a lower version: it must be ignored.
        publishSnapshot("workforce.employee.created.v1", UUID.randomUUID(), 104, 1, "older@shift.dk", "ACTIVE");

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(accounts.findByEmployeeId(104).orElseThrow().email()).isEqualTo("newer@shift.dk"));
        assertThat(accounts.findByEmployeeId(104).orElseThrow().aggregateVersion()).isEqualTo(2);
    }

    @Test
    void anInactiveEmployeeGetsADisabledAccount() {
        publishSnapshot("workforce.employee.created.v1", UUID.randomUUID(), 105, 1, "gone@shift.dk", "INACTIVE");

        assertThat(awaitAccount(105).orElseThrow().active()).isFalse();
    }
}
