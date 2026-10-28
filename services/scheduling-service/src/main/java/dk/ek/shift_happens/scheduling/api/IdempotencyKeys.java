package dk.ek.shift_happens.scheduling.api;

import dk.ek.shift_happens.scheduling.application.InvalidIdempotencyKeyException;

import java.util.UUID;

/** The Idempotency-Key header is a UUID in every POST of the contract (contracts/README.md). */
final class IdempotencyKeys {

    private IdempotencyKeys() {
    }

    static void requireUuid(String idempotencyKey) {
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            throw new InvalidIdempotencyKeyException(idempotencyKey);
        }
    }
}
