package dk.ek.shift_happens.leave.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ledger rules, without Spring. */
class LedgerEntryTest {

    private static final Instant NOW = Instant.parse("2027-01-01T10:00:00Z");

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    private static LeaveBalance balance(long last, String days) {
        return new LeaveBalance(last, d(days));
    }

    private static LedgerEntry next(LeaveBalance previous, TransactionType type, String amount) {
        return LedgerEntry.next(7, 3, previous, type, d(amount), null, null, NOW);
    }

    @Test
    void theNextEntryGetsTheNextNumberAndTheNewBalance() {
        LedgerEntry entry = next(balance(4, "10.00"), TransactionType.ACCRUAL, "2.5");

        assertThat(entry.entryNumber()).isEqualTo(5);
        assertThat(entry.balanceAfterDays()).isEqualByComparingTo("12.50");
        assertThat(entry.changeAmountDays()).isEqualTo(d("2.50"));
        assertThat(entry.leaveLedgerId()).isNull();
        assertThat(entry.employeeId()).isEqualTo(7);
        assertThat(entry.leaveTypeId()).isEqualTo(3);
        assertThat(entry.transactionDatetime()).isEqualTo(NOW);
    }

    @Test
    void theFirstEntryIsNumberOne() {
        assertThat(next(LeaveBalance.EMPTY, TransactionType.ACCRUAL, "1").entryNumber()).isEqualTo(1);
    }

    @Test
    void anEntryThatEmptiesTheBalanceExactlyIsAllowedButOneDayMoreIsNot() {
        assertThat(next(balance(1, "5.00"), TransactionType.ADJUSTMENT, "-5").balanceAfterDays())
                .isEqualByComparingTo("0");
        assertThatThrownBy(() -> next(balance(1, "5.00"), TransactionType.ADJUSTMENT, "-5.01"))
                .isInstanceOf(InsufficientLeaveBalanceException.class);
        assertThatThrownBy(() -> next(LeaveBalance.EMPTY, TransactionType.RESERVATION, "-1"))
                .isInstanceOf(InsufficientLeaveBalanceException.class);
    }

    @Test
    void onlyAccrualAndAdjustmentCanBePostedByHand() {
        LedgerEntry.requireManual(TransactionType.ACCRUAL);
        LedgerEntry.requireManual(TransactionType.ADJUSTMENT);
        assertThatThrownBy(() -> LedgerEntry.requireManual(TransactionType.RESERVATION))
                .isInstanceOf(InvalidLeaveRequestException.class);
        assertThatThrownBy(() -> LedgerEntry.requireManual(TransactionType.REVERSAL))
                .isInstanceOf(InvalidLeaveRequestException.class);
        assertThatThrownBy(() -> LedgerEntry.requireManual(null)).isInstanceOf(InvalidLeaveRequestException.class);
    }

    @Test
    void theSignDependsOnTheType() {
        LedgerEntry.requireValidAmount(TransactionType.ACCRUAL, d("0.01"));
        LedgerEntry.requireValidAmount(TransactionType.ADJUSTMENT, d("1"));
        LedgerEntry.requireValidAmount(TransactionType.ADJUSTMENT, d("-1"));
        LedgerEntry.requireValidAmount(TransactionType.RESERVATION, d("-3"));
        LedgerEntry.requireValidAmount(TransactionType.REVERSAL, d("3"));

        assertThatThrownBy(() -> LedgerEntry.requireValidAmount(TransactionType.ACCRUAL, d("-1")))
                .isInstanceOf(InvalidLeaveRequestException.class);
        assertThatThrownBy(() -> LedgerEntry.requireValidAmount(TransactionType.RESERVATION, d("1")))
                .isInstanceOf(InvalidLeaveRequestException.class);
        assertThatThrownBy(() -> LedgerEntry.requireValidAmount(TransactionType.REVERSAL, d("-1")))
                .isInstanceOf(InvalidLeaveRequestException.class);
    }

    @Test
    void zeroNullTooManyDecimalsAndHugeAmountsAreInvalid() {
        for (TransactionType type : TransactionType.values()) {
            assertThatThrownBy(() -> LedgerEntry.requireValidAmount(type, BigDecimal.ZERO))
                    .as("zero %s", type).isInstanceOf(InvalidLeaveRequestException.class);
            assertThatThrownBy(() -> LedgerEntry.requireValidAmount(type, null))
                    .as("null %s", type).isInstanceOf(InvalidLeaveRequestException.class);
        }
        assertThatThrownBy(() -> LedgerEntry.requireValidAmount(TransactionType.ADJUSTMENT, d("1.005")))
                .isInstanceOf(InvalidLeaveRequestException.class).hasMessageContaining("decimals");
        assertThatThrownBy(() -> LedgerEntry.requireValidAmount(TransactionType.ACCRUAL, d("100000")))
                .isInstanceOf(InvalidLeaveRequestException.class).hasMessageContaining("range");
        // trailing zeros are not decimals
        LedgerEntry.requireValidAmount(TransactionType.ACCRUAL, d("1.500"));
        LedgerEntry.requireValidAmount(TransactionType.ACCRUAL, d("99999.99"));
    }

    @Test
    void aSnapshotIsDueOnEveryFiftiethEntry() {
        assertThat(LedgerEntry.shouldSnapshot(1)).isFalse();
        assertThat(LedgerEntry.shouldSnapshot(49)).isFalse();
        assertThat(LedgerEntry.shouldSnapshot(50)).isTrue();
        assertThat(LedgerEntry.shouldSnapshot(51)).isFalse();
        assertThat(LedgerEntry.shouldSnapshot(99)).isFalse();
        assertThat(LedgerEntry.shouldSnapshot(100)).isTrue();
        assertThat(LedgerEntry.shouldSnapshot(150)).isTrue();
    }

    @Test
    void theSnapshotCarriesTheBalanceAfterTheEntry() {
        LedgerEntry entry = next(balance(49, "3.00"), TransactionType.ACCRUAL, "1");

        assertThat(entry.shouldSnapshot()).isTrue();
        BalanceSnapshot snapshot = entry.snapshot(NOW);
        assertThat(snapshot.entryNumber()).isEqualTo(50);
        assertThat(snapshot.balanceDays()).isEqualByComparingTo("4.00");
        assertThat(snapshot.employeeId()).isEqualTo(7);
        assertThat(snapshot.leaveTypeId()).isEqualTo(3);
    }

    @Test
    void anUnsavedEntryCannotBePublished() {
        assertThatThrownBy(() -> next(LeaveBalance.EMPTY, TransactionType.ACCRUAL, "1").recorded())
                .isInstanceOf(IllegalStateException.class);
    }
}
