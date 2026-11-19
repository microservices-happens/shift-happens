-- Leave types publish leave.type.{created,updated,deleted}.v1 (#46 / P2.15): they need an aggregate version, and a
-- delete becomes soft so requests and ledger entries keep a valid reference (the tombstone is the event).
-- Types that exist before this migration are not announced; there is no backfill (nothing is deployed yet).
ALTER TABLE leave_type
    ADD COLUMN aggregate_version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN deleted_at        TIMESTAMPTZ;

-- A deleted type's name can be reused: uniqueness only covers the types that are not deleted.
DROP INDEX idx_leave_type_name;
CREATE UNIQUE INDEX idx_leave_type_name ON leave_type (lower(leave_type_name)) WHERE deleted_at IS NULL;
