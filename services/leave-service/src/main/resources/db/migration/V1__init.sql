-- Leave command side, foundation (PR #44a): infrastructure tables only. The leave domain tables
-- (requests, approvals, ledger, saga, employee replica) arrive with their own migrations.
-- DDL copied from scheduling-service on purpose: patterns are copied per service, not shared.

-- Transactional outbox: the event is written in the same transaction as the state change, and a
-- relay publishes it afterwards (contracts/README.md).
CREATE TABLE outbox_event
(
    event_id     UUID PRIMARY KEY,
    event_type   VARCHAR(120) NOT NULL,
    aggregate_id BIGINT       NOT NULL,
    payload      TEXT         NOT NULL,
    occurred_at  TIMESTAMPTZ  NOT NULL,
    published_at TIMESTAMPTZ,
    attempts     INT          NOT NULL DEFAULT 0
);

CREATE INDEX idx_outbox_unpublished ON outbox_event (occurred_at) WHERE published_at IS NULL;

-- Idempotent consumers record the eventIds they applied, in the same transaction as their change.
CREATE TABLE processed_events
(
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Idempotent commands (contracts/README.md, parameter IdempotencyKey): the first response for a key
-- is stored for 24 hours and replayed, and the same key with a different request body is rejected with 409.
CREATE TABLE idempotency_record
(
    idempotency_key VARCHAR(64) PRIMARY KEY,
    request_hash    VARCHAR(64) NOT NULL,
    response_status INT         NOT NULL,
    response_body   TEXT        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_idempotency_created_at ON idempotency_record (created_at);
