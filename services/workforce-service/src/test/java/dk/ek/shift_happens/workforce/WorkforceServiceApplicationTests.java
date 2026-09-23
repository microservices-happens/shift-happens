package dk.ek.shift_happens.workforce;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkforceServiceApplicationTests {

    @Autowired
    private TestRestTemplate http;

    // The gateway, Compose and Kubernetes probes route traffic based on GET /health
    // (contracts/README.md). If this path moves, the service silently drops out of rotation.
    @Test
    void healthEndpointIsPublicAndUp() {
        ResponseEntity<String> response = http.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
