-- Identity read model for the employee replica (P2.2): accounts created from workforce.employee.*.
-- This service owns these tables; Workforce never reads them (contracts/README.md).

CREATE TABLE account
(
    account_id       BIGSERIAL PRIMARY KEY,
    employee_id      BIGINT       NOT NULL UNIQUE,
    employee_number  VARCHAR(50),
    email            VARCHAR(255) NOT NULL,
    role             VARCHAR(20)  NOT NULL,
    active           BOOLEAN      NOT NULL,
    -- The highest aggregateVersion applied; older snapshots are ignored (commutative handler).
    aggregate_version BIGINT      NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_account_email ON account (email);

-- Idempotent consumer: an eventId is recorded in the same transaction as the change it caused.
CREATE TABLE processed_events
(
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
