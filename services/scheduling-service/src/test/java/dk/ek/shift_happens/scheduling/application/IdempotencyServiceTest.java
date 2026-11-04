package dk.ek.shift_happens.scheduling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Idempotent commands: a replay returns the first response, and a changed body is a conflict. */
class IdempotencyServiceTest {

    private final InMemoryStore store = new InMemoryStore();
    private final IdempotencyService service = new IdempotencyService(store, new ObjectMapper());

    @Test
    void theFirstCallRunsAndItsResponseIsStored() {
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<?> response = service.execute(key(), new Body("a"), () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Body("done"));
        });

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(store.records).hasSize(1);
    }

    @Test
    void aReplayReturnsTheStoredResponseWithoutRunningAgain() {
        String key = key();
        AtomicInteger calls = new AtomicInteger();
        service.execute(key, new Body("a"), () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Body("done"));
        });

        ResponseEntity<?> replay = service.execute(key, new Body("a"), () -> {
            calls.incrementAndGet();
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new Body("again"));
        });

        assertThat(calls.get()).isEqualTo(1);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody()).asString().contains("done");
    }

    @Test
    void theSameKeyWithADifferentBodyIsAConflict() {
        String key = key();
        service.execute(key, new Body("a"), () -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new Body("done")));

        assertThatThrownBy(() -> service.execute(key, new Body("b"),
                () -> ResponseEntity.status(HttpStatus.ACCEPTED).body(new Body("done"))))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request body");
    }

    @Test
    void aKeyThatIsNotAUuidIsRejected() {
        assertThatThrownBy(() -> service.execute("not-a-uuid", new Body("a"),
                () -> ResponseEntity.ok().build()))
                .isInstanceOf(InvalidIdempotencyKeyException.class);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    record Body(String value) {
    }

    private static final class InMemoryStore implements IdempotencyStore {

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
