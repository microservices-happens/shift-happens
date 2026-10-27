package dk.ek.shift_happens.workforce.application;

import java.util.Optional;

/**
 * Port for storing the first response of an idempotent command (contracts/README.md:
 * "Idempotency-Key"). The adapter keeps the record for 24 hours.
 */
public interface IdempotencyStore {

    Optional<StoredResponse> find(String idempotencyKey);

    void save(String idempotencyKey, String requestHash, int responseStatus, String responseBody);

    /** {@code requestHash} is the SHA-256 of the normalised request body. */
    record StoredResponse(String requestHash, int status, String body) {
    }
}
