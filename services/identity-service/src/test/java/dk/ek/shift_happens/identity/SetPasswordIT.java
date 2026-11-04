package dk.ek.shift_happens.identity;

import dk.ek.shift_happens.identity.application.TokenIssuer;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * P2.4 end to end: PUT /v1/auth/accounts/{employeeId}/password with the role rules from the JWT, and
 * the first-admin bootstrap, which is what lets the very first person log in at all.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SetPasswordIT {

    private static final String BOOTSTRAP_EMAIL = "root@shift.dk";
    private static final String BOOTSTRAP_PASSWORD = "bootstrap-secret";

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
        registry.add("app.password.pepper", () -> "it-pepper");
        registry.add("app.bootstrap-admin.email", () -> BOOTSTRAP_EMAIL);
        registry.add("app.bootstrap-admin.password", () -> BOOTSTRAP_PASSWORD);
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private TokenIssuer tokens;
    @Value("${app.events.exchange}")
    private String exchange;

    @Test
    void theBootstrapAdminCanLogInOnceItsWorkforceEventArrives() {
        // The account arrives after startup, as it does in a fresh Compose stack.
        Account admin = employee(601, BOOTSTRAP_EMAIL, "Administrator");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(login(BOOTSTRAP_EMAIL, BOOTSTRAP_PASSWORD).getStatusCode()).isEqualTo(HttpStatus.OK));

        // And from there the admin can give everyone else a password: the whole point of the bootstrap.
        employee(602, "first.employee@shift.dk", "Employee");
        assertThat(setPassword(admin, 602, "employee-pass").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(login("first.employee@shift.dk", "employee-pass").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anEmployeeCanSetTheirOwnPasswordAndLogInWithIt() {
        Account self = employee(611, "self@shift.dk", "Employee");

        assertThat(setPassword(self, 611, "my-own-pass").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(login("self@shift.dk", "my-own-pass").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anEmployeeCannotSetSomeoneElsesPassword() {
        Account attacker = employee(621, "attacker@shift.dk", "Employee");
        employee(622, "victim@shift.dk", "Employee");

        ResponseEntity<Map> response = setPassword(attacker, 622, "taken-over");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(accounts.findByEmployeeId(622).orElseThrow().passwordHash()).isNull();
    }

    @Test
    void anAdministratorCanSetAnyonesPassword() {
        Account admin = employee(631, "admin@shift.dk", "Administrator");
        employee(632, "forgetful@shift.dk", "Manager");

        assertThat(setPassword(admin, 632, "reset-by-admin").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(login("forgetful@shift.dk", "reset-by-admin").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void anUnknownEmployeeIs404ProblemJson() {
        Account admin = employee(641, "admin2@shift.dk", "Administrator");

        ResponseEntity<Map> response = setPassword(admin, 999_999, "whatever-pass");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void aShortPasswordIs400AndKeepsTheOldOne() {
        Account self = employee(651, "short@shift.dk", "Employee");
        assertThat(setPassword(self, 651, "long-enough").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map> response = setPassword(self, 651, "1234567");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(login("short@shift.dk", "long-enough").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void aStaleWorkforceSaveCannotWipeANewPassword() {
        // The projection and PUT /password can run at the same time. A projection that read the account
        // before the password was set must not write the old (empty) hash back over the new one.
        Account self = employee(661, "stale@shift.dk", "Employee");
        Account staleCopy = accounts.findByEmployeeId(661).orElseThrow();
        assertThat(setPassword(self, 661, "fresh-password").getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        accounts.save(staleCopy);

        assertThat(login("stale@shift.dk", "fresh-password").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return http.postForEntity("/v1/auth/login", Map.of("email", email, "password", password), Map.class);
    }

    private ResponseEntity<Map> setPassword(Account caller, long employeeId, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(tokens.issueFor(caller));
        return http.exchange("/v1/auth/accounts/" + employeeId + "/password", HttpMethod.PUT,
                new HttpEntity<>(Map.of("password", password), headers), Map.class);
    }

    /** Publishes the workforce event and waits for the account: accounts only come from workforce. */
    private Account employee(long employeeId, String email, String role) {
        String body = """
                {"eventId":"%s","eventType":"workforce.employee.created.v1","occurredAt":"2026-11-04T08:00:00Z",
                 "producer":"workforce-service","aggregateId":%d,"aggregateVersion":1,
                 "correlationId":"set-password-it","causationId":null,
                 "data":{"employeeId":%d,"employeeNumber":"EMP-%d","userRole":"%s",
                         "firstName":"Grace","lastName":"Hopper","email":"%s","employmentStatus":"ACTIVE",
                         "jobRoleIds":[]}}
                """.formatted(UUID.randomUUID(), employeeId, employeeId, employeeId, role, email);
        rabbitTemplate.send(exchange, "workforce.employee.created.v1", MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build());

        await().atMost(Duration.ofSeconds(20)).until(() -> accounts.findByEmployeeId(employeeId).isPresent());
        return accounts.findByEmployeeId(employeeId).orElseThrow();
    }
}
