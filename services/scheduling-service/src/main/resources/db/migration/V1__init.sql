-- Scheduling write model, slice 1 (P2.7): shifts, their required job roles and the outbox.
--
-- Ownership: only scheduling-service reads these tables (contracts/README.md). department_id,
-- work_location_id and job_role_id are Workforce ids, so they are plain columns here and never a
-- foreign key across a service boundary (the monolith did have those FKs; a microservice must not).
-- Ported from the monolith schema (src/main/resources/db/mysql/schema.sql, shift tables), with
-- MySQL AUTO_INCREMENT -> BIGSERIAL, DATETIME -> TIMESTAMPTZ and the cross-service FKs dropped.

CREATE TABLE shift
(
    shift_id          BIGSERIAL PRIMARY KEY,
    department_id     BIGINT       NOT NULL,
    work_location_id  BIGINT       NOT NULL,
    shift_name        VARCHAR(255) NOT NULL,
    start_datetime    TIMESTAMPTZ  NOT NULL,
    end_datetime      TIMESTAMPTZ  NOT NULL,
    shift_status      VARCHAR(20)  NOT NULL,
    -- Increments on every change of the aggregate; consumers keep the highest version
    -- (contracts/README.md, "Commutative consumers").
    aggregate_version BIGINT       NOT NULL DEFAULT 1,
    -- Soft delete; the tombstone event arrives with scheduling.shift.deleted.v1.
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    -- Half-open intervals [start, end) are what the overlap rule uses; the DB enforces a duration.
    CONSTRAINT shift_has_a_positive_duration CHECK (end_datetime > start_datetime)
);

-- The week view and the overlap check both filter on the time window.
CREATE INDEX idx_shift_window ON shift (start_datetime, end_datetime);
CREATE INDEX idx_shift_department_location ON shift (department_id, work_location_id);

-- One row per job role the shift needs. Replaces the monolith's shiftrequiredjobrole table.
CREATE TABLE shift_required_job_role
(
    shift_required_job_role_id BIGSERIAL PRIMARY KEY,
    shift_id                   BIGINT NOT NULL REFERENCES shift (shift_id) ON DELETE CASCADE,
    job_role_id                BIGINT NOT NULL,
    required_employee_count    INT    NOT NULL DEFAULT 1,
    CONSTRAINT shift_job_role_is_unique UNIQUE (shift_id, job_role_id),
    CONSTRAINT required_count_is_positive CHECK (required_employee_count >= 1)
);

-- Transactional outbox: the event is written in the same transaction as the state change, and a
-- relay publishes it afterwards (contracts/README.md, docs/architecture.md section 4.3).
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
