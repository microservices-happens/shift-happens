package dk.ek.shift_happens.leave;

import dk.ek.shift_happens.leave.application.IdempotencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two simultaneous commands with the same Idempotency-Key must run the action once. The first action is held
 * open on a latch; the second call must then either already be finished (broken) or be waiting for the
 * advisory lock of the key in PostgreSQL (correct), never running its own action.
 */
class IdempotencyConcurrencyIT extends AbstractCommandIT {

    @Autowired
    private IdempotencyService idempotency;

    @Test
    void twoConcurrentCallsWithTheSameKeyRunTheActionOnce() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("employeeId", 1);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<?>> a = pool.submit(() -> idempotency.execute(key, body, () -> {
                int run = runs.incrementAndGet();
                entered.countDown();
                awaitLatch(release);
                return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("run", run));
            }));
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();

            Future<ResponseEntity<?>> b = pool.submit(() -> idempotency.execute(key, body,
                    () -> ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("run", runs.incrementAndGet()))));
            await().atMost(Duration.ofSeconds(15)).until(() -> b.isDone() || waitingForAnAdvisoryLock() > 0);
            release.countDown();

            ResponseEntity<?> first = a.get(15, TimeUnit.SECONDS);
            ResponseEntity<?> second = b.get(15, TimeUnit.SECONDS);

            assertThat(runs.get()).as("the action must run exactly once").isEqualTo(1);
            assertThat(second.getStatusCode()).isEqualTo(first.getStatusCode());
            assertThat(objectMapper.readTree(second.getBody().toString())).isEqualTo(objectMapper.valueToTree(first.getBody()));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_record WHERE idempotency_key = ?",
                Integer.class, key)).isEqualTo(1);
    }

    private int waitingForAnAdvisoryLock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' AND wait_event = 'advisory'",
                Integer.class);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
