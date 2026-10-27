package dk.ek.shift_happens.scheduling.application;

/** Thrown when the Idempotency-Key header is present but is not a UUID (contracts/README.md). */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String idempotencyKey) {
        super("Idempotency-Key must be a UUID, got: " + idempotencyKey);
    }
}
