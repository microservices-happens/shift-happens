package dk.ek.shift_happens.workforce.application;

/** The contract declares Idempotency-Key as a UUID (format: uuid), so anything else is a 400. */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException(String idempotencyKey) {
        super("Idempotency-Key must be a UUID, but was '" + idempotencyKey + "'");
    }
}
