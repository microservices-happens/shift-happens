package dk.ek.shift_happens.cooperation;

import dk.ek.shift_happens.cooperation.support.ServiceContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The system-level cooperation test (final-project description section 5): two real services and
 * RabbitMQ in containers, asserting that they cooperate through messaging.
 *
 * <p>Flow: POST an employee to workforce-service → workforce writes its outbox row and the relay
 * publishes {@code workforce.employee.created.v1} → identity-service consumes it and creates an
 * account. The assertions read each service's own database, so nothing is mocked and no service is
 * asked to expose a non-contract endpoint.
 *
 * <p>It is also the cooperation proof of P1.7: the POST carries a JWT that identity-service issued at
 * {@code POST /v1/auth/login}, and workforce-service accepts it after fetching identity's JWKS from
 * {@code JWT_JWKS_URL}. The first Administrator cannot be created through workforce (that write needs a
 * token), so its {@code workforce.employee.created.v1} event is published straight to the exchange and
 * identity's first-admin bootstrap gives it a password.
 */
class EmployeeReplicationIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final DockerImageName POSTGRES = DockerImageName.parse("postgres:17-alpine");
    private static final String EXCHANGE = "shift-happens.events";
    private static final long ADMIN_ID = 9000;
    private static final String ADMIN_EMAIL = "admin-9000@shift.dk";
    private static final String ADMIN_PASSWORD = "cooperation-admin-password";

    private static RabbitMQContainer rabbit;
    private static PostgreSQLContainer<?> workforceDb;
    private static PostgreSQLContainer<?> identityDb;
    private static GenericContainer<?> workforce;
    private static GenericContainer<?> identity;

    @BeforeAll
    static void startStack() {
        ServiceContainer.requireDockerOrSkip();

        rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
                .withNetwork(NETWORK)
                .withNetworkAliases("rabbitmq")
                .withAdminUser("shift")
                .withAdminPassword("shift_dev");
        rabbit.start();

        workforceDb = new PostgreSQLContainer<>(POSTGRES)
                .withNetwork(NETWORK).withNetworkAliases("workforce-db")
                .withDatabaseName("workforce").withUsername("workforce").withPassword("workforce_dev");
        identityDb = new PostgreSQLContainer<>(POSTGRES)
                .withNetwork(NETWORK).withNetworkAliases("identity-db")
                .withDatabaseName("identity").withUsername("identity").withPassword("identity_dev");
        workforceDb.start();
        identityDb.start();

        workforce = ServiceContainer.create("workforce-service", NETWORK)
                .withEnv(serviceEnvironment("workforce-db", "workforce", "workforce", "workforce_dev", null))
                // As in docker-compose.microservices.yml: the JWKS comes from the real identity-service.
                .withEnv("JWT_ISSUER", "shift-happens-identity")
                .withEnv("JWT_JWKS_URL", "http://identity-service:8080/.well-known/jwks.json");
        identity = ServiceContainer.create("identity-service", NETWORK)
                .withEnv(serviceEnvironment("identity-db", "identity", "identity", "identity_dev", "identity.accounts"))
                .withEnv("PASSWORD_PEPPER", "cooperation-test-pepper")
                .withEnv("BOOTSTRAP_ADMIN_EMAIL", ADMIN_EMAIL)
                .withEnv("BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD);

        workforce.start();
        identity.start();
    }

    @AfterAll
    static void stopStack() {
        close(identity);
        close(workforce);
        close(identityDb);
        close(workforceDb);
        close(rabbit);
        NETWORK.close();
    }

    @Test
    void anEmployeeCreatedInWorkforceBecomesAnAccountInIdentity() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "replication-" + suffix + "@shift.dk";
        String body = """
                {"employeeNumber":"EMP-%s","userRole":"Employee","firstName":"Ada","lastName":"Lovelace",
                 "email":"%s","employmentStatus":"ACTIVE","hireDate":"2024-01-15"}
                """.formatted(suffix, email);

        // 0. A token issued by identity-service is what workforce-service accepts; without one it refuses.
        String token = loginAsTheBootstrapAdministrator();
        assertThat(post(workforce, "/v1/employees", body, null).statusCode()).isEqualTo(401);
        assertThat(count(workforceDb, "SELECT count(*) FROM employee")).isZero();

        HttpResponse<String> created = post(workforce, "/v1/employees", body, token);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);

        // 1. Workforce committed the employee and its outbox relay published the event.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(count(workforceDb,
                "SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL")).isEqualTo(1));

        // 2. Identity consumed the event and created the account (eventual consistency).
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(count(identityDb,
                "SELECT count(*) FROM account WHERE email = '" + email + "'")).isEqualTo(1));

        assertThat(count(identityDb, "SELECT count(*) FROM account WHERE email = '" + email
                + "' AND active = true AND role = 'Employee'")).isEqualTo(1);
        // Each event was handled exactly once: the bootstrap Administrator's and this employee's.
        assertThat(count(identityDb, "SELECT count(*) FROM processed_events")).isEqualTo(2);
    }

    /**
     * The Administrator reaches identity as an event (see the class comment), gets the bootstrap password,
     * and logs in through the real login endpoint. Returns the JWT.
     */
    private static String loginAsTheBootstrapAdministrator() throws Exception {
        publishAdministratorCreated();
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(count(identityDb,
                "SELECT count(*) FROM account WHERE email = '" + ADMIN_EMAIL + "' AND password_hash IS NOT NULL"))
                .isEqualTo(1));

        HttpResponse<String> login = post(identity, "/v1/auth/login",
                "{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}", null);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        Matcher token = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"").matcher(login.body());
        assertThat(token.find()).as("token in the login response").isTrue();
        return token.group(1);
    }

    /** workforce.employee.created.v1 as workforce-service publishes it (contracts/events/payloads/employee.schema.json). */
    private static void publishAdministratorCreated() throws Exception {
        String payload = """
                {"eventId":"%s","eventType":"workforce.employee.created.v1","occurredAt":"%s",
                 "producer":"workforce-service","aggregateId":%d,"aggregateVersion":1,
                 "correlationId":"%s","data":{"employeeId":%d,"employeeNumber":"EMP-%d","userRole":"Administrator",
                 "firstName":"Grace","lastName":"Hopper","email":"%s","employmentStatus":"ACTIVE",
                 "jobRoleIds":[]}}
                """.formatted(UUID.randomUUID(), Instant.now(), ADMIN_ID, UUID.randomUUID(), ADMIN_ID, ADMIN_ID,
                ADMIN_EMAIL);
        String body = "{\"properties\":{\"content_type\":\"application/json\"},\"routing_key\":\"workforce.employee."
                + "created.v1\",\"payload\":" + jsonString(payload) + ",\"payload_encoding\":\"string\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(rabbit.getHttpUrl() + "/api/exchanges/%2F/" + EXCHANGE
                        + "/publish"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("shift:shift_dev".getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        // HTTP/1.1: the management listener does not take the h2c upgrade the default client attempts
        HttpResponse<String> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).as("the exchange routed the event to identity's queue").contains("true");
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static Map<String, String> serviceEnvironment(String dbHost, String dbName, String dbUser,
                                                          String dbPassword, String queue) {
        Map<String, String> env = new HashMap<>();
        env.put("DB_HOST", dbHost);
        env.put("DB_PORT", "5432");
        env.put("DB_NAME", dbName);
        env.put("DB_USER", dbUser);
        env.put("DB_PASSWORD", dbPassword);
        env.put("RABBITMQ_HOST", "rabbitmq");
        env.put("RABBITMQ_PORT", "5672");
        env.put("RABBITMQ_USER", "shift");
        env.put("RABBITMQ_PASSWORD", "shift_dev");
        env.put("RABBITMQ_VHOST", "/");
        if (queue != null) {
            env.put("RABBITMQ_QUEUE", queue);
        }
        return env;
    }

    /** POST with the bearer token, or without any Authorization header when {@code token} is null. */
    private static HttpResponse<String> post(GenericContainer<?> service, String path, String body, String token)
            throws Exception {
        URI uri = URI.create("http://" + service.getHost() + ":"
                + service.getMappedPort(ServiceContainer.HTTP_PORT) + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                // Every POST requires a per-action key (contracts/README.md, Idempotency).
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static long count(PostgreSQLContainer<?> database, String sql) {
        try (Connection connection = DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }

    private static void close(AutoCloseable container) {
        if (container == null) {
            return;
        }
        try {
            container.close();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
