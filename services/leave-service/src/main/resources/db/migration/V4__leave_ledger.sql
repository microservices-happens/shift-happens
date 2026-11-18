-- Immutable leave ledger and balance snapshots (#45).
-- The balance of an employee for one leave type is the sum of its ledger entries. Entries are numbered
-- 1, 2, 3... per (employee, leave type) and carry the balance after them; every 50th entry also gets a
-- snapshot, so a balance is read as "newest snapshot + the entries after it". employee_id is a Workforce id
-- (no cross-service foreign key).

CREATE TABLE leave_ledger
(
    leave_ledger_id       BIGSERIAL PRIMARY KEY,
    employee_id           BIGINT        NOT NULL,
    leave_type_id         BIGINT        NOT NULL REFERENCES leave_type (leave_type_id),
    entry_number          BIGINT        NOT NULL CHECK (entry_number > 0),
    change_amount_days    NUMERIC(7, 2) NOT NULL,
    balance_after_days    NUMERIC(9, 2) NOT NULL,
    transaction_type      VARCHAR(20)   NOT NULL,
    reference_entity_type VARCHAR(50),
    reference_entity_id   BIGINT,
    transaction_datetime  TIMESTAMPTZ   NOT NULL,
    CONSTRAINT chk_leave_ledger_amount CHECK (change_amount_days <> 0),
    CONSTRAINT chk_leave_ledger_type CHECK (transaction_type IN ('ACCRUAL', 'ADJUSTMENT', 'RESERVATION', 'REVERSAL')),
    -- no gaps and no duplicates per employee and leave type; the application serializes appends with a lock,
    -- this is the backstop
    CONSTRAINT uq_leave_ledger_entry UNIQUE (employee_id, leave_type_id, entry_number)
);

-- A leave request is reserved at most once and reversed at most once (saga idempotency backstop).
CREATE UNIQUE INDEX ux_leave_ledger_one_reservation ON leave_ledger (reference_entity_id)
    WHERE transaction_type = 'RESERVATION' AND reference_entity_type = 'LEAVE_REQUEST';
CREATE UNIQUE INDEX ux_leave_ledger_one_reversal ON leave_ledger (reference_entity_id)
    WHERE transaction_type = 'REVERSAL' AND reference_entity_type = 'LEAVE_REQUEST';
CREATE INDEX idx_leave_ledger_reference ON leave_ledger (reference_entity_type, reference_entity_id);
CREATE INDEX idx_leave_ledger_type ON leave_ledger (leave_type_id);

CREATE TABLE balance_snapshot
(
    employee_id   BIGINT        NOT NULL,
    leave_type_id BIGINT        NOT NULL,
    entry_number  BIGINT        NOT NULL,
    balance_days  NUMERIC(9, 2) NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (employee_id, leave_type_id, entry_number)
);

-- Append-only in the database too: a bug (or a hand-written statement) must not be able to rewrite history.
-- A correction is a new REVERSAL/ADJUSTMENT entry, never an UPDATE. The same holds for snapshots, which are
-- derived from the ledger and only ever written once.
CREATE FUNCTION refuse_ledger_change() RETURNS trigger AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_leave_ledger_append_only
    BEFORE UPDATE OR DELETE ON leave_ledger
    FOR EACH ROW EXECUTE FUNCTION refuse_ledger_change();
CREATE TRIGGER trg_leave_ledger_no_truncate
    BEFORE TRUNCATE ON leave_ledger
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_ledger_change();
CREATE TRIGGER trg_balance_snapshot_append_only
    BEFORE UPDATE OR DELETE ON balance_snapshot
    FOR EACH ROW EXECUTE FUNCTION refuse_ledger_change();
CREATE TRIGGER trg_balance_snapshot_no_truncate
    BEFORE TRUNCATE ON balance_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_ledger_change();
