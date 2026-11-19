package dk.ek.shift_happens.leave.domain;

/**
 * Kinds of ledger entry. ACCRUAL and ADJUSTMENT can be posted by hand; RESERVATION and REVERSAL belong to the
 * leave-approval saga (a leave request reserves its days, a compensation reverses them).
 */
public enum TransactionType {
    ACCRUAL(true),
    ADJUSTMENT(true),
    RESERVATION(false),
    REVERSAL(false);

    private final boolean manual;

    TransactionType(boolean manual) {
        this.manual = manual;
    }

    public boolean isManual() {
        return manual;
    }
}
