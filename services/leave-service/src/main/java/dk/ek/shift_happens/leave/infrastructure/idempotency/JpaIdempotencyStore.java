package dk.ek.shift_happens.leave.infrastructure.idempotency;

import dk.ek.shift_happens.leave.application.IdempotencyStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Adapter for {@link IdempotencyStore}: records live for 24 hours (contracts/README.md, parameter
 * IdempotencyKey). An expired record is ignored and overwritten by the next command with the same key.
 */
@Component
@Profile("command")
class JpaIdempotencyStore implements IdempotencyStore {

    /** First int of the advisory-lock key, so it cannot collide with other two-int advisory locks. */
    private static final int LOCK_CLASS_ID = 4401;
    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyRecordJpaRepository repository;

    JpaIdempotencyStore(IdempotencyRecordJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void lock(String idempotencyKey) {
        repository.lock(LOCK_CLASS_ID, idempotencyKey.hashCode());
    }

    @Override
    public Optional<StoredResponse> find(String idempotencyKey) {
        Instant oldestKept = Instant.now().minus(RETENTION);
        return repository.findById(idempotencyKey)
                .filter(record -> record.getCreatedAt().isAfter(oldestKept))
                .map(record -> new StoredResponse(record.getRequestHash(), record.getResponseStatus(),
                        record.getResponseBody()));
    }

    @Override
    public void save(String idempotencyKey, String requestHash, int responseStatus, String responseBody) {
        IdempotencyRecordEntity record = repository.findById(idempotencyKey)
                .map(existing -> {
                    existing.overwrite(requestHash, responseStatus, responseBody);
                    return existing;
                })
                .orElseGet(() -> IdempotencyRecordEntity.create(idempotencyKey, requestHash, responseStatus,
                        responseBody));
        repository.save(record);
    }
}
