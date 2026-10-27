package dk.ek.shift_happens.workforce.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit test of the idempotency rule; no Spring context and no database. */
class IdempotencyServiceTest {

    private final InMemoryIdempotencyStore store = new InMemoryIdempotencyStore();
    private final IdempotencyService service = new IdempotencyService(store, new ObjectMapper());

    @Test
    void replaysTheStoredResponseWithoutRunningTheActionAgain() {
        AtomicInteger executions = new AtomicInteger();

        ResponseEntity<?> first = service.execute("key-1", Map.of("email", "ada@shift.dk"), () -> {
            executions.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("employeeId", 7));
        });
        ResponseEntity<?> replay = service.execute("key-1", Map.of("email", "ada@shift.dk"), () -> {
            executions.incrementAndGet();
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("employeeId", 8));
        });

        assertThat(executions.get()).isEqualTo(1);
        assertThat(first.getBody()).isEqualTo(Map.of("employeeId", 7));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isEqualTo("{\"employeeId\":7}");
    }

    @Test
    void theSameKeyWithADifferentBodyIsAConflict() {
        service.execute("key-2", Map.of("email", "ada@shift.dk"),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("employeeId", 1)));

        assertThatThrownBy(() -> service.execute("key-2", Map.of("email", "grace@shift.dk"),
                () -> ResponseEntity.status(HttpStatus.CREATED).body(Map.of("employeeId", 2))))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    private static final class InMemoryIdempotencyStore implements IdempotencyStore {

        private final Map<String, StoredResponse> records = new HashMap<>();

        @Override
        public Optional<StoredResponse> find(String idempotencyKey) {
            return Optional.ofNullable(records.get(idempotencyKey));
        }

        @Override
        public void save(String idempotencyKey, String requestHash, int responseStatus, String responseBody) {
            records.put(idempotencyKey, new StoredResponse(requestHash, responseStatus, responseBody));
        }
    }
}
