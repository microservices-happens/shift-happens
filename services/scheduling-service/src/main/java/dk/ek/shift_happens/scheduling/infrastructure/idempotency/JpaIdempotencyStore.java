package dk.ek.shift_happens.scheduling.infrastructure.idempotency;

import dk.ek.shift_happens.scheduling.application.IdempotencyStore;
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

    private static final Duration RETENTION = Duration.ofHours(24);

    private final IdempotencyRecordJpaRepository repository;

    JpaIdempotencyStore(IdempotencyRecordJpaRepository repository) {
        this.repository = repository;
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
