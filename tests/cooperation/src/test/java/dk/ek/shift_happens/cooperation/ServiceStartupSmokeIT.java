package dk.ek.shift_happens.cooperation;

import static org.assertj.core.api.Assertions.assertThat;

import dk.ek.shift_happens.cooperation.support.ServiceContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Infrastructure smoke test for the cooperation harness: two real service containers and RabbitMQ
 * on one Docker network.
 *
 * <p>It proves the setup every later cooperation test relies on (images build, services start,
 * DNS names and the broker work) without asserting business behaviour that is not implemented yet.
 * The business-level test is {@link LeaveApprovalSagaIT}.
 */
class ServiceStartupSmokeIT {

    private static final Network NETWORK = Network.newNetwork();

    private static RabbitMQContainer rabbit;
    private static GenericContainer<?> identity;
    private static GenericContainer<?> workforce;

    @BeforeAll
    static void startStack() {
        ServiceContainer.requireDockerOrSkip();

        rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
                .withNetwork(NETWORK)
                .withNetworkAliases("rabbitmq");
        rabbit.start();

        identity = ServiceContainer.create("identity-service", NETWORK);
        workforce = ServiceContainer.create("workforce-service", NETWORK);
        identity.start();
        workforce.start();
    }

    @AfterAll
    static void stopStack() {
        stop(identity);
        stop(workforce);
        stop(rabbit);
        NETWORK.close();
    }

    @Test
    void servicesAndBrokerAreRunning() {
        assertThat(identity.isRunning()).isTrue();
        assertThat(workforce.isRunning()).isTrue();
        assertThat(rabbit.isRunning()).isTrue();
        assertThat(rabbit.getMappedPort(5672)).isPositive();
    }

    @Test
    void servicesReachEachOtherAndTheBrokerByServiceName() throws Exception {
        // The gateway, consumers and every future cooperation test address services by their
        // container DNS name, never by localhost. This is the network contract we depend on.
        assertHealthFrom(identity, "http://workforce-service:8080/health");
        assertHealthFrom(workforce, "http://identity-service:8080/health");

        Container.ExecResult resolution = identity.execInContainer("getent", "hosts", "rabbitmq");
        assertThat(resolution.getExitCode())
                .as("rabbitmq must resolve inside the service network: %s", resolution.getStderr())
                .isZero();
        assertThat(resolution.getStdout()).contains("rabbitmq");
    }

    private static void assertHealthFrom(GenericContainer<?> from, String url) throws Exception {
        Container.ExecResult result = from.execInContainer("curl", "-fsS", url);
        assertThat(result.getExitCode())
                .as("GET %s from %s: %s", url, from.getDockerImageName(), result.getStderr())
                .isZero();
        assertThat(result.getStdout()).contains("\"status\":\"UP\"");
    }

    private static void stop(AutoCloseable container) {
        if (container == null) {
            return;
        }
        try {
            container.close();
        } catch (Exception ignored) {
            // Best-effort cleanup; the test result is what matters.
        }
    }
}
