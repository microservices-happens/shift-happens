-- Workforce write model, slice 1 (P2.5/P2.6).
-- The schema is owned by this service only; no other service reads these tables (contracts/README.md).

CREATE TABLE employee
(
    employee_id              BIGSERIAL PRIMARY KEY,
    employee_number          VARCHAR(50)  NOT NULL UNIQUE,
    user_role                VARCHAR(20)  NOT NULL,
    first_name               VARCHAR(100) NOT NULL,
    last_name                VARCHAR(100) NOT NULL,
    email                    VARCHAR(255) NOT NULL UNIQUE,
    phone_number             VARCHAR(50),
    hire_date                DATE,
    employment_status        VARCHAR(50)  NOT NULL,
    primary_work_location_id BIGINT,
    -- Increments on every change; consumers keep the snapshot with the highest version.
    aggregate_version        BIGINT       NOT NULL DEFAULT 1,
    deleted                  BOOLEAN      NOT NULL DEFAULT FALSE
);

-- Transactional outbox: the event is written in the same transaction as the state change, and a
-- relay publishes it afterwards (contracts/README.md, docs/architecture.md section 2.4.7).
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

CREATE INDEX idx_outbox_unpublished ON outbox_event (occurred_at) WHERE published_at IS NULL;
