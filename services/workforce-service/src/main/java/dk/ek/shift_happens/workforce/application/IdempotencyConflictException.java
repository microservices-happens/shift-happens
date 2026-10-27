package dk.ek.shift_happens.workforce.application;

/** Raised when an Idempotency-Key is reused with a different request body (contracts: 409). */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key " + idempotencyKey + " was already used with a different request body");
    }
}
