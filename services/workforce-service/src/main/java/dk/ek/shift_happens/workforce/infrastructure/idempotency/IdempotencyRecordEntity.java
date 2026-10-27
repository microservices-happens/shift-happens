package dk.ek.shift_happens.workforce.infrastructure.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** JPA mapping for idempotency_record (V2__idempotency.sql). */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    @Column(name = "response_body", nullable = false, columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecordEntity() {
        // for JPA
    }

    static IdempotencyRecordEntity create(String idempotencyKey, String requestHash, int responseStatus,
                                          String responseBody) {
        IdempotencyRecordEntity entity = new IdempotencyRecordEntity();
        entity.idempotencyKey = idempotencyKey;
        entity.requestHash = requestHash;
        entity.responseStatus = responseStatus;
        entity.responseBody = responseBody;
        entity.createdAt = Instant.now();
        return entity;
    }

    void overwrite(String requestHash, int responseStatus, String responseBody) {
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.createdAt = Instant.now();
    }

    String getRequestHash() {
        return requestHash;
    }

    int getResponseStatus() {
        return responseStatus;
    }

    String getResponseBody() {
        return responseBody;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
