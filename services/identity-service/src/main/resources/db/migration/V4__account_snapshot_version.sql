-- Commutative account projection (P3.3): the result must not depend on the order
-- workforce.employee.* events arrive in.

-- The version of the snapshot the workforce fields (email, role, names, active) come from.
-- aggregate_version stays the highest version seen, snapshot or tombstone, so
-- aggregate_version > snapshot_version means "deleted by a tombstone newer than every snapshot".
-- With both, a snapshot that arrives after the tombstone still refreshes the fields without
-- switching the account back on.
ALTER TABLE account ADD COLUMN snapshot_version BIGINT;

-- Existing rows: the fields were written by the version the row holds. (An account that was
-- already disabled by a tombstone stays disabled through "active"; like before this migration,
-- only a snapshot with a higher version changes it.)
UPDATE account SET snapshot_version = aggregate_version;

ALTER TABLE account ALTER COLUMN snapshot_version SET NOT NULL;

-- A tombstone can arrive before the create. It is stored as an account without workforce fields
-- (snapshot_version 0), so the late create cannot resurrect a deleted employee. Such a row has no
-- email or role yet; the unique indexes ignore NULLs.
ALTER TABLE account ALTER COLUMN email DROP NOT NULL;
ALTER TABLE account ALTER COLUMN role DROP NOT NULL;
