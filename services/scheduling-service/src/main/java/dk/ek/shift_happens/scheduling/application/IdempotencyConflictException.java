package dk.ek.shift_happens.scheduling.application;

/** Thrown when a key was used before with a different request body. Maps to 409. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key " + idempotencyKey + " was already used with a different request body");
    }
}
