package dk.ek.shift_happens.leave;

import dk.ek.shift_happens.leave.application.IdempotencyStore;
import dk.ek.shift_happens.leave.domain.ProcessedEventLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the service against real Postgres with Flyway and {@code ddl-auto: validate}, so a drift
 * between V1__init.sql and the JPA entities fails here and not in production.
 */
class PersistenceFoundationIT extends AbstractCommandIT {

    @Autowired
    private IdempotencyStore idempotencyStore;
    @Autowired
    private ProcessedEventLog processedEventLog;
    @Autowired
    private TestRestTemplate http;

    @Test
    void aStoredIdempotentResponseIsReadBackSoARetryCanBeReplayed() {
        String key = UUID.randomUUID().toString();

        idempotencyStore.save(key, "hash-1", 201, "{\"id\":1}");

        assertThat(idempotencyStore.find(key)).hasValueSatisfying(stored -> {
            assertThat(stored.requestHash()).isEqualTo("hash-1");
            assertThat(stored.status()).isEqualTo(201);
            assertThat(stored.body()).isEqualTo("{\"id\":1}");
        });
        assertThat(idempotencyStore.find(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void aProcessedEventIsRememberedSoARedeliveryCanBeSkipped() {
        UUID eventId = UUID.randomUUID();
        assertThat(processedEventLog.contains(eventId)).isFalse();

        processedEventLog.record(eventId);

        assertThat(processedEventLog.contains(eventId)).isTrue();
    }

    // The gateway, Compose and Kubernetes probes route traffic based on GET /health
    // (contracts/README.md). If this path moves, the service silently drops out of rotation.
    @Test
    void healthEndpointIsPublicAndUp() {
        var response = http.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
