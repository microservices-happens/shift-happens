package dk.ek.shift_happens.leave.application;

import java.util.Optional;

/**
 * Port for storing the first response of an idempotent command (contracts/README.md:
 * "Idempotency-Key"). The adapter keeps the record for 24 hours.
 */
public interface IdempotencyStore {

    /**
     * Blocks until no other transaction holds the same key, and holds it until the current transaction ends.
     * Must be called inside a transaction.
     */
    void lock(String idempotencyKey);

    Optional<StoredResponse> find(String idempotencyKey);

    void save(String idempotencyKey, String requestHash, int responseStatus, String responseBody);

    /** {@code requestHash} is the SHA-256 of the normalised request body. */
    record StoredResponse(String requestHash, int status, String body) {
    }
}
