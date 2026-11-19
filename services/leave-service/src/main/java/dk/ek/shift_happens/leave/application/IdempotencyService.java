package dk.ek.shift_happens.leave.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Idempotent commands, as required by contracts/README.md: a repeated {@code Idempotency-Key} returns
 * the stored first response and causes no second side effect, while the same key with a different
 * body is a 409.
 *
 * <p>The response body is stored as JSON, so a replay is byte-for-byte the first response.
 *
 * <p>{@link #execute} is one transaction: it first takes a transaction-scoped advisory lock on the key, so two
 * simultaneous calls with the same key run one after the other, and the second one finds the first one's record.
 * The action's own transactional methods join this transaction. A failing action rolls back its effects together
 * with the record, so failures are never stored and a retry with the same key runs again.
 */
@Service
@Profile("command")
public class IdempotencyService {

    private final IdempotencyStore store;
    private final ObjectMapper mapper;

    IdempotencyService(IdempotencyStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Transactional
    public ResponseEntity<?> execute(String rawIdempotencyKey, Object requestBody,
                                     Supplier<ResponseEntity<?>> action) {
        // The canonical form makes "ABC..." and "abc..." (and lenient inputs such as 1-1-1-1-1) one key.
        String idempotencyKey = requireUuid(rawIdempotencyKey).toString();
        String requestHash = hash(requestBody);

        store.lock(idempotencyKey);
        Optional<IdempotencyStore.StoredResponse> stored = store.find(idempotencyKey);
        if (stored.isPresent()) {
            if (!stored.get().requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return ResponseEntity.status(stored.get().status())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(stored.get().body());
        }

        ResponseEntity<?> response = action.get();
        store.save(idempotencyKey, requestHash, response.getStatusCode().value(), json(response.getBody()));
        return response;
    }

    private UUID requireUuid(String idempotencyKey) {
        try {
            return UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException exception) {
            throw new InvalidIdempotencyKeyException(idempotencyKey);
        }
    }

    private String hash(Object requestBody) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(requestBody));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("cannot hash the request body", exception);
        }
    }

    private String json(Object body) {
        if (body instanceof String text) {
            return text;
        }
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize the response body", exception);
        }
    }
}
