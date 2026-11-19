package dk.ek.shift_happens.leave.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One immutable row of the leave ledger. Entries are numbered 1, 2, 3... per (employee, leave type) and carry
 * the balance after them. A correction is a new entry with the opposite amount, never a change.
 */
public record LedgerEntry(
        Long leaveLedgerId,
        long employeeId,
        long leaveTypeId,
        long entryNumber,
        BigDecimal changeAmountDays,
        BigDecimal balanceAfterDays,
        TransactionType transactionType,
        String referenceEntityType,
        Long referenceEntityId,
        Instant transactionDatetime) {

    /** A snapshot is written after every 50th entry of an employee and leave type. */
    public static final int SNAPSHOT_INTERVAL = 50;

    /** The reference type of entries that belong to a leave request. */
    public static final String LEAVE_REQUEST = "LEAVE_REQUEST";

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("99999.99");
    private static final BigDecimal MAX_BALANCE = new BigDecimal("9999999.99");

    /** POST /v1/leaveledgers accepts only ACCRUAL and ADJUSTMENT; the other types belong to the saga. */
    public static void requireManual(TransactionType type) {
        if (type == null) {
            throw new InvalidLeaveRequestException("transactionType is required");
        }
        if (!type.isManual()) {
            throw new InvalidLeaveRequestException(type + " entries are written by the leave-approval saga and "
                    + "cannot be posted by hand; use ACCRUAL or ADJUSTMENT");
        }
    }

    /**
     * Pure shape rules for an amount of a given type: at most two decimals, within the column range, and the
     * sign the type demands (ACCRUAL and REVERSAL positive, RESERVATION negative, ADJUSTMENT either but not zero).
     */
    public static void requireValidAmount(TransactionType type, BigDecimal amount) {
        if (amount == null) {
            throw new InvalidLeaveRequestException("changeAmountDays is required");
        }
        if (amount.signum() == 0) {
            throw new InvalidLeaveRequestException("changeAmountDays must not be zero");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new InvalidLeaveRequestException("changeAmountDays has more than 2 decimals: "
                    + amount.toPlainString());
        }
        if (amount.abs().compareTo(MAX_AMOUNT) > 0) {
            throw new InvalidLeaveRequestException("changeAmountDays is out of range: " + amount.toPlainString());
        }
        boolean positive = amount.signum() > 0;
        switch (type) {
            case ACCRUAL, REVERSAL -> {
                if (!positive) {
                    throw new InvalidLeaveRequestException(type + " must be positive");
                }
            }
            case RESERVATION -> {
                if (positive) {
                    throw new InvalidLeaveRequestException("RESERVATION must be negative");
                }
            }
            case ADJUSTMENT -> {
                // either sign
            }
        }
    }

    /**
     * The entry that follows {@code previous}: its number is the next one and its balance is the previous
     * balance plus the amount. Refuses (409) an entry that would make the balance negative.
     */
    public static LedgerEntry next(long employeeId, long leaveTypeId, LeaveBalance previous, TransactionType type,
                                   BigDecimal changeAmountDays, String referenceEntityType, Long referenceEntityId,
                                   Instant now) {
        requireValidAmount(type, changeAmountDays);
        BigDecimal amount = changeAmountDays.setScale(2);
        BigDecimal balanceAfter = previous.balanceDays().add(amount);
        if (balanceAfter.signum() < 0) {
            throw new InsufficientLeaveBalanceException("insufficient leave balance: " + previous.balanceDays()
                    + " days available for employee " + employeeId + " and leave type " + leaveTypeId + ", "
                    + amount + " requested");
        }
        if (balanceAfter.compareTo(MAX_BALANCE) > 0) {
            throw new InvalidLeaveRequestException("the balance would exceed " + MAX_BALANCE.toPlainString());
        }
        return new LedgerEntry(null, employeeId, leaveTypeId, previous.lastEntryNumber() + 1, amount, balanceAfter,
                type, referenceEntityType, referenceEntityId, now);
    }

    /** True when a snapshot of the balance after this entry is due. */
    public boolean shouldSnapshot() {
        return shouldSnapshot(entryNumber);
    }

    public static boolean shouldSnapshot(long entryNumber) {
        return entryNumber % SNAPSHOT_INTERVAL == 0;
    }

    public BalanceSnapshot snapshot(Instant createdAt) {
        return new BalanceSnapshot(employeeId, leaveTypeId, entryNumber, balanceAfterDays, createdAt);
    }

    /** data of leave.ledger.entry-recorded.v1 (contracts/events/payloads/ledger-entry.schema.json). */
    public LedgerEntryRecorded recorded() {
        if (leaveLedgerId == null) {
            throw new IllegalStateException("a ledger entry must be saved before it can be published");
        }
        return new LedgerEntryRecorded(leaveLedgerId, employeeId, leaveTypeId, changeAmountDays, balanceAfterDays,
                transactionType.name(), referenceEntityType, referenceEntityId, transactionDatetime);
    }
}
