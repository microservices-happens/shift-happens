-- The two inputs scheduling.shift.understaffed.v1 needs from other services. Both arrive as events:
-- weekly_contract_hours from workforce.employee.* (contracts/events/payloads/employee.schema.json) and the
-- approved leave windows from leave.request.approved.v1 / .cancelled.v1. No synchronous call.

ALTER TABLE employee_replica
    ADD COLUMN weekly_contract_hours DOUBLE PRECISION NOT NULL DEFAULT 0;

CREATE TABLE leave_window
(
    leave_request_id  BIGINT PRIMARY KEY,
    employee_id       BIGINT      NOT NULL,
    start_date        DATE        NOT NULL,
    end_date          DATE        NOT NULL,
    -- APPROVED (the employee is away) or CANCELLED (the window no longer applies).
    status            VARCHAR(20) NOT NULL,
    aggregate_version BIGINT      NOT NULL DEFAULT 1,
    updated_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_leave_window_employee ON leave_window (employee_id, start_date, end_date);
