package dk.ek.shift_happens.cooperation.support;

import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A real service image built from {@code services/<name>/Dockerfile}.
 *
 * <p>Images are named {@code shift-happens/<service>:<tag>}. CI builds them with the {@code ci} tag
 * and sets {@code COOPERATION_REQUIRE_IMAGES=true}, so a missing image fails the build instead of
 * silently skipping the test. Locally the test is skipped with an instruction when the image is
 * absent.
 */
public final class ServiceContainer {

    public static final int HTTP_PORT = 8080;

    private static final Logger log = LoggerFactory.getLogger(ServiceContainer.class);

    private ServiceContainer() {
    }

    /**
     * Creates (but does not start) a container for {@code service} on {@code network}, waiting
     * until the shared {@code GET /health} contract returns 200.
     */
    public static GenericContainer<?> create(String service, Network network) {
        DockerImageName image = DockerImageName.parse(imageName(service));
        requireImage(image);

        log.info("Preparing {} on the shared cooperation network", image);
        return new GenericContainer<>(image)
                .withNetwork(network)
                .withNetworkAliases(service)
                .withExposedPorts(HTTP_PORT)
                .waitingFor(Wait.forHttp("/health")
                        .forPort(HTTP_PORT)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    private static String imageName(String service) {
        String tag = System.getenv().getOrDefault("SERVICE_IMAGE_TAG", "ci");
        return "shift-happens/" + service + ":" + tag;
    }

    /**
     * Skips the calling test class when no Docker daemon is reachable, or fails when
     * {@code COOPERATION_REQUIRE_IMAGES=true} (CI) so a broken runner cannot look green.
     */
    public static void requireDockerOrSkip() {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            return;
        }
        failOrSkip("Docker is not available. Cooperation tests need a running Docker daemon.");
    }

    private static void requireImage(DockerImageName image) {
        if (DockerClientFactory.instance().isDockerAvailable() && imageExists(image.toString())) {
            return;
        }

        if (!DockerClientFactory.instance().isDockerAvailable()) {
            failOrSkip("Docker is not available. Cooperation tests need a running Docker daemon.");
        }

        failOrSkip("Docker image " + image + " not found. Build it first: docker build -t " + image
                + " services/" + image.getRepository().replace("shift-happens/", ""));
    }

    private static void failOrSkip(String message) {
        if (Boolean.parseBoolean(System.getenv().getOrDefault("COOPERATION_REQUIRE_IMAGES", "false"))) {
            throw new IllegalStateException(message);
        }
        Assumptions.assumeTrue(false, message);
    }

    private static boolean imageExists(String name) {
        try {
            DockerClientFactory.instance().client().inspectImageCmd(name).exec();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
