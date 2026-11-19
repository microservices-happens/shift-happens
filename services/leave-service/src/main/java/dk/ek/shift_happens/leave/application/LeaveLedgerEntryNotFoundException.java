package dk.ek.shift_happens.leave.application;

/** Thrown when a ledger entry id does not exist (path id). Maps to 404. */
public class LeaveLedgerEntryNotFoundException extends RuntimeException {

    public LeaveLedgerEntryNotFoundException(long leaveLedgerId) {
        super("no leave ledger entry with id " + leaveLedgerId);
    }
}
