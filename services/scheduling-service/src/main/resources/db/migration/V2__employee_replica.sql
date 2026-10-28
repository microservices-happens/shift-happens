-- Scheduling's local employee replica, built from workforce.employee.* events (P2.8, replica pattern).
-- No foreign keys to Workforce: it is another service with another database (contracts/README.md).
-- job_role_ids is a comma-separated list because the replica only needs membership tests.

CREATE TABLE employee_replica
(
    employee_id              BIGINT PRIMARY KEY,
    first_name               VARCHAR(100),
    last_name                VARCHAR(100),
    employment_status        VARCHAR(50),
    job_role_ids             TEXT,
    primary_work_location_id BIGINT,
    -- Highest aggregateVersion applied; older snapshots are ignored (commutative consumer).
    aggregate_version        BIGINT      NOT NULL DEFAULT 1,
    -- Retained tombstone: set by workforce.employee.deleted.v1 and never cleared.
    deleted_at               TIMESTAMPTZ
);

-- Idempotent consumers record the eventIds they applied, in the same transaction as their change.
CREATE TABLE processed_events
(
    event_id     UUID PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
