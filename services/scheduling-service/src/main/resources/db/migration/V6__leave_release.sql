-- Saga participant state (P3.1): one row per leave request this service answered, so a redelivered
-- leave.request.approval-started.v1 re-publishes the same reply instead of releasing twice.

CREATE TABLE leave_release
(
    leave_request_id        BIGINT PRIMARY KEY,
    employee_id             BIGINT      NOT NULL,
    start_date              DATE        NOT NULL,
    end_date                DATE        NOT NULL,
    result                  VARCHAR(20) NOT NULL,
    -- Comma-separated assignment ids; empty for a rejection.
    released_assignment_ids TEXT,
    reason                  TEXT,
    created_at              TIMESTAMPTZ NOT NULL
);
