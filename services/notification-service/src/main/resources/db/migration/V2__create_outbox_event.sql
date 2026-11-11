
-- Transactional outbox for notification-service.
-- Email request events are stored here before being published to RabbitMQ.

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

CREATE INDEX idx_outbox_unpublished
    ON outbox_event (occurred_at)
    WHERE published_at IS NULL;
