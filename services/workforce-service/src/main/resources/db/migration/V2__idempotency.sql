-- Idempotent commands (contracts/README.md, contracts/openapi/workforce.yaml parameter
-- IdempotencyKey): the first response for a key is stored for 24 hours and replayed, and the same
-- key with a different request body is rejected with 409.

CREATE TABLE idempotency_record
(
    idempotency_key VARCHAR(64) PRIMARY KEY,
    request_hash    VARCHAR(64) NOT NULL,
    response_status INT         NOT NULL,
    response_body   TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_idempotency_created_at ON idempotency_record (created_at);