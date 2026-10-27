package dk.ek.shift_happens.cooperation.support;

import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts a real service image (built from {@code services/<name>/Dockerfile}) on a shared network.
 *
 * <p>The image is {@code shift-happens/<service>:<tag>}, where the tag comes from
 * {@code SERVICE_IMAGE_TAG} (default {@code ci}). Without Docker, or without the image built
 * locally, the test is skipped with the command to build it; CI sets
 * {@code COOPERATION_REQUIRE_IMAGES=true} so a missing image there fails loudly instead.
 */
public final class ServiceContainer {

    public static final int HTTP_PORT = 8080;

    private ServiceContainer() {
    }

    /** Skips the calling test class when no Docker daemon is reachable. */
    public static void requireDockerOrSkip() {
        if (!DockerClientFactory.instance().isDockerAvailable()) {
            skipOrFail("Docker is not available. The cooperation tests need a running Docker daemon.");
        }
    }

    /** Creates (but does not start) a container that waits until {@code GET /health} returns 200. */
    public static GenericContainer<?> create(String service, Network network) {
        String tag = System.getenv().getOrDefault("SERVICE_IMAGE_TAG", "ci");
        DockerImageName image = DockerImageName.parse("shift-happens/" + service + ":" + tag);

        if (DockerClientFactory.instance().isDockerAvailable() && !imageExists(image.toString())) {
            skipOrFail("Docker image " + image + " not found. Build it first: docker build -t "
                    + image + " services/" + service);
        }

        return new GenericContainer<>(image)
                .withNetwork(network)
                .withNetworkAliases(service)
                .withExposedPorts(HTTP_PORT)
                .waitingFor(Wait.forHttp("/health")
                        .forPort(HTTP_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    private static boolean imageExists(String image) {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(image).exec();
            return true;
        } catch (RuntimeException notFound) {
            return false;
        }
    }

    private static void skipOrFail(String message) {
        if (Boolean.parseBoolean(System.getenv().getOrDefault("COOPERATION_REQUIRE_IMAGES", "false"))) {
            throw new IllegalStateException(message);
        }
        Assumptions.assumeTrue(false, message);
    }
}
