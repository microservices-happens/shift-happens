-- Shift swaps (P2.9). A swap moves an existing assignment to a colleague, so it lives here and the
-- approval is one local transaction (docs/architecture.md). employee ids are Workforce ids: plain
-- columns, never a cross-service foreign key. shift_id is copied in because
-- contracts/events/payloads/swap.schema.json carries it.

CREATE TABLE shift_swap
(
    shift_swap_id                BIGSERIAL PRIMARY KEY,
    original_shift_assignment_id BIGINT      NOT NULL REFERENCES shift_assignment (shift_assignment_id),
    shift_id                     BIGINT      NOT NULL REFERENCES shift (shift_id),
    employee_from_id             BIGINT      NOT NULL,
    employee_to_id               BIGINT      NOT NULL,
    swap_status                  VARCHAR(20) NOT NULL,
    request_datetime             TIMESTAMPTZ NOT NULL,
    reason                       TEXT,
    aggregate_version            BIGINT      NOT NULL DEFAULT 1
);

-- An assignment can have at most one open swap request at a time.
CREATE UNIQUE INDEX idx_swap_pending ON shift_swap (original_shift_assignment_id)
    WHERE swap_status = 'PENDING';

CREATE TABLE shift_swap_approval
(
    shift_swap_approval_id BIGSERIAL PRIMARY KEY,
    shift_swap_id          BIGINT      NOT NULL UNIQUE REFERENCES shift_swap (shift_swap_id),
    approver_employee_id   BIGINT      NOT NULL,
    decision               VARCHAR(20) NOT NULL,
    shift_swap_comment     TEXT,
    decision_datetime      TIMESTAMPTZ
);
