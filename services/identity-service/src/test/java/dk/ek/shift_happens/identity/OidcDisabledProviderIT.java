package dk.ek.shift_happens.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The default deployment: no OIDC_* client ids are set (as in Compose without a .env). Neither provider
 * may then redirect anyone to Google or GitHub with an empty client id.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcDisabledProviderIT {

    private static final String FRONTEND_CALLBACK = "https://frontend.test/login/callback";

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
        registry.add("app.oidc.frontend-callback-url", () -> FRONTEND_CALLBACK);
        registry.add("app.oidc.google.client-id", () -> "");
        registry.add("app.oidc.github.client-id", () -> "");
    }

    private final HttpClient browser = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void aProviderWithoutClientIdIs400ProblemJson() throws Exception {
        for (String provider : new String[] {"google", "github"}) {
            HttpResponse<String> response = get("/v1/auth/oidc/" + provider + "/authorize");

            assertThat(response.statusCode()).as(provider).isEqualTo(400);
            assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
            assertThat(response.headers().firstValue("Location")).isEmpty();
        }
    }

    @Test
    void aCallbackForADisabledProviderGoesBackToTheFrontendWithAnError() throws Exception {
        HttpResponse<String> response = get("/v1/auth/oidc/google/callback?code=c&state=s");

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Location")).hasValue(FRONTEND_CALLBACK + "#error=unknown_provider");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
