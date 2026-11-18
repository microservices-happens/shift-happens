package dk.ek.shift_happens.leave.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The balance is "newest snapshot + the entries after it". An in-memory ledger appends entries the way the
 * service does (read the balance through the snapshot, build the next entry, snapshot every 50th) and records
 * how much it had to read, so the tests prove both the sum and that the snapshot path is really used.
 */
class LeaveBalanceTest {

    private static final Instant NOW = Instant.parse("2027-01-01T10:00:00Z");

    /** Plays the repository: all entries, the snapshots and the size of the tail the last read used. */
    private static final class InMemoryLedger {
        final List<LedgerEntry> entries = new ArrayList<>();
        final List<BalanceSnapshot> snapshots = new ArrayList<>();
        int lastTailSize = -1;
        boolean lastReadUsedSnapshot;

        LeaveBalance balance() {
            Optional<BalanceSnapshot> newest = snapshots.stream()
                    .max(java.util.Comparator.comparingLong(BalanceSnapshot::entryNumber));
            long after = newest.map(BalanceSnapshot::entryNumber).orElse(0L);
            List<LedgerEntry> tail = entries.stream().filter(e -> e.entryNumber() > after).toList();
            lastTailSize = tail.size();
            lastReadUsedSnapshot = newest.isPresent();
            return LeaveBalance.of(newest, tail);
        }

        void append(String amount) {
            LedgerEntry entry = LedgerEntry.next(1, 1, balance(), TransactionType.ADJUSTMENT,
                    new BigDecimal(amount), null, null, NOW);
            entries.add(entry);
            if (entry.shouldSnapshot()) {
                snapshots.add(entry.snapshot(NOW));
            }
        }

        BigDecimal plainSum() {
            return entries.stream().map(LedgerEntry::changeAmountDays).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** The amounts vary (1.25, 2.50, -0.75 ...) and the first entry is positive, so the balance stays >= 0. */
    private static InMemoryLedger ledgerWith(int entries) {
        InMemoryLedger ledger = new InMemoryLedger();
        String[] amounts = {"5.25", "-0.75", "1.5", "-1", "2"};
        for (int i = 0; i < entries; i++) {
            ledger.append(amounts[i % amounts.length]);
        }
        return ledger;
    }

    @Test
    void anEmptyLedgerHasABalanceOfZeroAtEntryZero() {
        LeaveBalance balance = LeaveBalance.of(Optional.empty(), List.of());

        assertThat(balance.balanceDays()).isEqualByComparingTo("0");
        assertThat(balance.lastEntryNumber()).isZero();
    }

    @Test
    void theBalanceEqualsThePlainSumAtEveryBoundaryAroundTheSnapshots() {
        for (int count : new int[] {0, 1, 49, 50, 51, 99, 100, 101, 149, 150, 151, 230}) {
            InMemoryLedger ledger = ledgerWith(count);

            LeaveBalance balance = ledger.balance();

            assertThat(balance.balanceDays()).as("%d entries", count).isEqualByComparingTo(ledger.plainSum());
            assertThat(balance.lastEntryNumber()).as("%d entries", count).isEqualTo(count);
            if (count > 0) {
                assertThat(ledger.entries.get(count - 1).balanceAfterDays()).as("%d entries", count)
                        .isEqualByComparingTo(ledger.plainSum());
            }
        }
    }

    @Test
    void snapshotsExistExactlyAtMultiplesOfFiftyAndKeepTheReadSmall() {
        InMemoryLedger ledger = ledgerWith(101);

        assertThat(ledger.snapshots).extracting(BalanceSnapshot::entryNumber).containsExactly(50L, 100L);
        ledger.balance();
        assertThat(ledger.lastReadUsedSnapshot).isTrue();
        assertThat(ledger.lastTailSize).as("only the entry after the snapshot at 100 is read").isEqualTo(1);

        assertThat(ledgerWith(49).snapshots).isEmpty();
        InMemoryLedger fifty = ledgerWith(50);
        assertThat(fifty.snapshots).hasSize(1);
        fifty.balance();
        assertThat(fifty.lastTailSize).as("the snapshot at 50 covers everything").isZero();
    }

    @Test
    void theSnapshotIsTrustedSoTheBalanceIsSnapshotPlusTail() {
        // a deliberately "wrong" snapshot proves the sum starts from the snapshot, not from the entries before it
        BalanceSnapshot snapshot = new BalanceSnapshot(1, 1, 50, new BigDecimal("1000.00"), NOW);
        LedgerEntry entry = new LedgerEntry(1L, 1, 1, 51, new BigDecimal("-2.50"), new BigDecimal("997.50"),
                TransactionType.ADJUSTMENT, null, null, NOW);

        LeaveBalance balance = LeaveBalance.of(Optional.of(snapshot), List.of(entry));

        assertThat(balance.balanceDays()).isEqualByComparingTo("997.50");
        assertThat(balance.lastEntryNumber()).isEqualTo(51);
    }

    @Test
    void anEntryAtOrBeforeTheSnapshotIsNotPartOfTheTail() {
        BalanceSnapshot snapshot = new BalanceSnapshot(1, 1, 50, new BigDecimal("10.00"), NOW);
        LedgerEntry stale = new LedgerEntry(1L, 1, 1, 50, new BigDecimal("1.00"), new BigDecimal("10.00"),
                TransactionType.ACCRUAL, null, null, NOW);

        assertThatThrownBy(() -> LeaveBalance.of(Optional.of(snapshot), List.of(stale)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
