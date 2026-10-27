package dk.ek.shift_happens.workforce.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Idempotent commands, as required by contracts/README.md and contracts/openapi/workforce.yaml:
 * a repeated {@code Idempotency-Key} returns the stored first response and causes no second side
 * effect, while the same key with a different body is a 409.
 *
 * <p>The response body is stored as JSON so that a replay is byte-for-byte the first response.
 */
@Service
public class IdempotencyService {

    private final IdempotencyStore store;
    private final ObjectMapper mapper;

    IdempotencyService(IdempotencyStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public ResponseEntity<?> execute(String idempotencyKey, Object requestBody, Supplier<ResponseEntity<?>> action) {
        String requestHash = hash(requestBody);

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
