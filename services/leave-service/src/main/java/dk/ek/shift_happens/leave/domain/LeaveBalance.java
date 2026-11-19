package dk.ek.shift_happens.leave.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The balance of one employee and leave type after {@code lastEntryNumber} entries: the sum of all their
 * change amounts. Computed as "newest snapshot + the entries after it", never by summing the whole ledger.
 */
public record LeaveBalance(long lastEntryNumber, BigDecimal balanceDays) {

    public static final LeaveBalance EMPTY = new LeaveBalance(0, BigDecimal.ZERO.setScale(2));

    /**
     * @param newest      the newest snapshot, if any
     * @param entriesAfter exactly the entries whose number is greater than the snapshot's (all of them without
     *                     a snapshot), in any order
     */
    public static LeaveBalance of(Optional<BalanceSnapshot> newest, List<LedgerEntry> entriesAfter) {
        long snapshotNumber = newest.map(BalanceSnapshot::entryNumber).orElse(0L);
        long last = snapshotNumber;
        BigDecimal balance = newest.map(BalanceSnapshot::balanceDays).orElse(BigDecimal.ZERO);
        for (LedgerEntry entry : entriesAfter) {
            if (entry.entryNumber() <= snapshotNumber) {
                throw new IllegalArgumentException("entry " + entry.entryNumber()
                        + " is not after the snapshot at " + snapshotNumber);
            }
            balance = balance.add(entry.changeAmountDays());
            last = Math.max(last, entry.entryNumber());
        }
        return new LeaveBalance(last, balance.setScale(2));
    }
}
