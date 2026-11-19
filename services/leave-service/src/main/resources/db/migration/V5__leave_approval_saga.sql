-- Leave-approval saga orchestrator (#47 / P3.1).
-- One row per saga attempt of a leave request: attempt 1 starts when a manager approves, and a late
-- scheduling.leave-release.completed.v1 after a timeout compensation starts attempt 2, 3 (see
-- contracts/events/catalog.md, "Flow: leave-approval saga"). Replies correlate on approval_started_event_id
-- (the eventId of the leave.request.approval-started.v1 of that attempt = the causationId of the reply).

CREATE TABLE leave_approval_saga
(
    leave_approval_saga_id    BIGSERIAL PRIMARY KEY,
    leave_request_id          BIGINT      NOT NULL REFERENCES leave_request (leave_request_id),
    attempt                   INTEGER     NOT NULL CHECK (attempt > 0),
    saga_status               VARCHAR(20) NOT NULL,
    approval_started_event_id UUID        NOT NULL,
    correlation_id            TEXT        NOT NULL,
    deadline                  TIMESTAMPTZ NOT NULL,
    created_at                TIMESTAMPTZ NOT NULL,
    updated_at                TIMESTAMPTZ NOT NULL,
    CONSTRAINT chk_leave_approval_saga_status CHECK (saga_status IN ('STARTED', 'COMPLETED', 'COMPENSATED', 'TIMED_OUT')),
    CONSTRAINT uq_leave_approval_saga_attempt UNIQUE (leave_request_id, attempt),
    CONSTRAINT uq_leave_approval_saga_event UNIQUE (approval_started_event_id)
);

-- At most one saga of a request is in flight.
CREATE UNIQUE INDEX ux_leave_approval_saga_one_started ON leave_approval_saga (leave_request_id)
    WHERE saga_status = 'STARTED';
-- The timeout job looks for overdue STARTED sagas.
CREATE INDEX idx_leave_approval_saga_due ON leave_approval_saga (deadline) WHERE saga_status = 'STARTED';

-- One RESERVATION and one REVERSAL per saga attempt (was: per request, V4), so a late completed reply after a
-- timeout compensation can reserve again. ADD COLUMN does not fire the row-level append-only trigger.
ALTER TABLE leave_ledger ADD COLUMN saga_attempt INTEGER CHECK (saga_attempt > 0);

DROP INDEX ux_leave_ledger_one_reservation;
DROP INDEX ux_leave_ledger_one_reversal;
CREATE UNIQUE INDEX ux_leave_ledger_one_reservation ON leave_ledger (reference_entity_id, saga_attempt)
    WHERE transaction_type = 'RESERVATION' AND reference_entity_type = 'LEAVE_REQUEST';
CREATE UNIQUE INDEX ux_leave_ledger_one_reversal ON leave_ledger (reference_entity_id, saga_attempt)
    WHERE transaction_type = 'REVERSAL' AND reference_entity_type = 'LEAVE_REQUEST';
