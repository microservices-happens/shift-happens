-- Local replica of workforce employees, fed by workforce.employee.* events (replica pattern,
-- contracts/README.md). Leave only keeps what it needs to validate a request: who the employee is
-- and whether they are still active.
-- deleted_at is the retained tombstone: the row stays (and may even be created by a delete that
-- arrives before the create), so a late, lower-version update can never resurrect the employee.
CREATE TABLE employee_replica
(
    employee_id        BIGINT PRIMARY KEY,
    first_name         VARCHAR(100),
    last_name          VARCHAR(100),
    employment_status  VARCHAR(50),
    aggregate_version  BIGINT      NOT NULL,
    deleted_at         TIMESTAMPTZ
);
