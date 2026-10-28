-- Assignments and their approvals (P2.7). employee_id and approver_employee_id are Workforce ids:
-- plain columns, never a foreign key across a service boundary. Ported from the monolith
-- (src/main/resources/db/mysql/schema.sql, shift_assignment / shift_approval).

CREATE TABLE shift_assignment
(
    shift_assignment_id BIGSERIAL PRIMARY KEY,
    shift_id            BIGINT      NOT NULL REFERENCES shift (shift_id),
    employee_id         BIGINT      NOT NULL,
    assignment_status   VARCHAR(30) NOT NULL,
    assigned_datetime   TIMESTAMPTZ,
    check_in_datetime   TIMESTAMPTZ,
    check_out_datetime  TIMESTAMPTZ,
    -- Increments on every change; consumers keep the highest version.
    aggregate_version   BIGINT      NOT NULL DEFAULT 1,
    -- Soft delete; the tombstone event arrives with scheduling.assignment.deleted.v1.
    deleted             BOOLEAN     NOT NULL DEFAULT FALSE
);

-- The overlap rule asks "what does this employee already work in this window?".
CREATE INDEX idx_assignment_employee ON shift_assignment (employee_id);
CREATE INDEX idx_assignment_shift ON shift_assignment (shift_id);

CREATE TABLE shift_approval
(
    shift_approval_id    BIGSERIAL PRIMARY KEY,
    shift_assignment_id  BIGINT      NOT NULL REFERENCES shift_assignment (shift_assignment_id),
    approver_employee_id BIGINT      NOT NULL,
    decision             VARCHAR(20) NOT NULL,
    approval_comment     TEXT,
    decision_datetime    TIMESTAMPTZ,
    -- One decision per assignment: a second one is a conflict, not another row.
    CONSTRAINT one_decision_per_assignment UNIQUE (shift_assignment_id)
);
