-- Commutative employee replica (P3.3): the row must not depend on the order
-- workforce.employee.* events arrive in.

-- The version of the snapshot the employee fields come from. aggregate_version stays the highest
-- version seen, snapshot or tombstone. With both, a snapshot that was published before the delete
-- but arrives after it still refreshes the fields, and a tombstone that arrives before the create
-- is stored as a row without employee fields (snapshot_version 0) that the late create cannot
-- clear.
ALTER TABLE employee_replica ADD COLUMN snapshot_version BIGINT NOT NULL DEFAULT 0;

-- Existing rows: the fields were written by the version the row holds.
UPDATE employee_replica SET snapshot_version = aggregate_version;
