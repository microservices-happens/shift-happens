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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
 */
class EmployeeReplicationIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final DockerImageName POSTGRES = DockerImageName.parse("postgres:17-alpine");

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
                .withEnv(serviceEnvironment("workforce-db", "workforce", "workforce", "workforce_dev", null));
        identity = ServiceContainer.create("identity-service", NETWORK)
                .withEnv(serviceEnvironment("identity-db", "identity", "identity", "identity_dev", "identity.accounts"));

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

        HttpResponse<String> created = post(workforce, "/v1/employees", body);
        assertThat(created.statusCode()).isEqualTo(201);

        // 1. Workforce committed the employee and its outbox relay published the event.
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(count(workforceDb,
                "SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL")).isEqualTo(1));

        // 2. Identity consumed the event and created the account (eventual consistency).
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(count(identityDb,
                "SELECT count(*) FROM account WHERE email = '" + email + "'")).isEqualTo(1));

        assertThat(count(identityDb, "SELECT count(*) FROM account WHERE email = '" + email
                + "' AND active = true AND role = 'Employee'")).isEqualTo(1);
        // The event was handled exactly once.
        assertThat(count(identityDb, "SELECT count(*) FROM processed_events")).isEqualTo(1);
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

    private static HttpResponse<String> post(GenericContainer<?> service, String path, String body) throws Exception {
        URI uri = URI.create("http://" + service.getHost() + ":"
                + service.getMappedPort(ServiceContainer.HTTP_PORT) + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                // Every POST requires a per-action key (contracts/README.md, Idempotency).
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
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
