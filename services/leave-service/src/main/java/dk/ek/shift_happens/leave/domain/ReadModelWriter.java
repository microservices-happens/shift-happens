package dk.ek.shift_happens.leave.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Port for the query role's projection (writes). The store is a standalone MongoDB without multi-document
 * transactions, so every method must be idempotent and guarded on its own: applying the same event twice, or events in
 * any order, ends in the same state.
 */
public interface ReadModelWriter {

    boolean isProcessed(UUID eventId);

    /** Recorded after the change has been applied; see the adapter for why that order is safe. */
    void markProcessed(UUID eventId);

    /** Stored only when {@code version} is newer than the stored one and the employee is not deleted. */
    void applyEmployee(long employeeId, String firstName, String lastName, long version);

    /** A tombstone always wins, also when the employee was never seen; the version only moves up. */
    void tombstoneEmployee(long employeeId, long version, Instant deletedAt);

    void applyLeaveType(LeaveTypeSnapshot snapshot, long version);

    void tombstoneLeaveType(long leaveTypeId, long version, Instant deletedAt);

    /** Stored only when {@code version} is newer than the stored one. */
    void applyLeaveRequest(LeaveRequestSnapshot snapshot, long version);

    /** Ledger entries are immutable: a second entry with the same id is the same entry and is ignored. */
    void recordLedgerEntry(LedgerEntryRecorded entry);
}
