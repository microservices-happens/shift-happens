package dk.ek.shift_happens.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Swagger UI serves the hand-written contract; the build must put it on the classpath (P8.2). */
class SwaggerContractTest {

    @Test
    void contractIsOnTheClasspath() throws IOException {
        var resource = new ClassPathResource("static/openapi/workforce.yaml");

        assertThat(resource.exists()).isTrue();
        assertThat(resource.getContentAsString(StandardCharsets.UTF_8)).contains("openapi: 3.1");
    }
}
