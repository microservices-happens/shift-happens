-- Leave command side (#44): leave types, leave requests and leave approvals.
-- employee ids are Workforce ids: plain columns, never a cross-service foreign key (the local
-- replica is checked by the application). The ledger, snapshots and saga tables arrive with #45/#47.

CREATE TABLE leave_type
(
    leave_type_id          BIGSERIAL PRIMARY KEY,
    leave_type_name        VARCHAR(100) NOT NULL,
    leave_type_description TEXT,
    requires_approval      BOOLEAN      NOT NULL DEFAULT TRUE,
    is_paid_leave          BOOLEAN      NOT NULL DEFAULT TRUE
);

-- Names are unique ignoring case ("Vacation" and "vacation" would confuse managers).
CREATE UNIQUE INDEX idx_leave_type_name ON leave_type (lower(leave_type_name));

CREATE TABLE leave_request
(
    leave_request_id   BIGSERIAL PRIMARY KEY,
    employee_id        BIGINT      NOT NULL,
    leave_type_id      BIGINT      NOT NULL REFERENCES leave_type (leave_type_id),
    start_date         DATE        NOT NULL,
    end_date           DATE        NOT NULL,
    request_status     VARCHAR(20) NOT NULL,
    reason             TEXT,
    rejection_reason   TEXT,
    requested_datetime TIMESTAMPTZ NOT NULL,
    aggregate_version  BIGINT      NOT NULL DEFAULT 1,
    CONSTRAINT chk_leave_request_dates CHECK (end_date >= start_date)
);

CREATE INDEX idx_leave_request_employee ON leave_request (employee_id);
CREATE INDEX idx_leave_request_type ON leave_request (leave_type_id);

-- Last line of defence behind the application's overlap check: one employee never has two active
-- requests that share a day. endDate is the last day of leave, hence the closed range '[]'.
CREATE EXTENSION IF NOT EXISTS btree_gist;
ALTER TABLE leave_request
    ADD CONSTRAINT excl_leave_request_overlap
        EXCLUDE USING gist (employee_id WITH =, daterange(start_date, end_date, '[]') WITH &&)
        WHERE (request_status IN ('PENDING', 'APPROVAL_PENDING', 'APPROVED'));

CREATE TABLE leave_approval
(
    leave_approval_id    BIGSERIAL PRIMARY KEY,
    leave_request_id     BIGINT      NOT NULL REFERENCES leave_request (leave_request_id),
    approver_employee_id BIGINT      NOT NULL,
    decision             VARCHAR(20) NOT NULL,
    leave_comment        TEXT,
    decision_datetime    TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_leave_approval_request ON leave_approval (leave_request_id);
