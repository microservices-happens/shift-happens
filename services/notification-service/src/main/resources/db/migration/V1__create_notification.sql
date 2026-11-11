CREATE TABLE notification
(
    notification_id   BIGSERIAL PRIMARY KEY,
    employee_id       BIGINT       NOT NULL,
    title             VARCHAR(255) NOT NULL,
    message           TEXT,
    source_event_type VARCHAR(120) NOT NULL,
    source_event_id   UUID         NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    is_read           BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_notification_employee
    ON notification (employee_id, created_at DESC);

CREATE INDEX idx_notification_unread
    ON notification (employee_id, created_at DESC)
    WHERE is_read = FALSE;
