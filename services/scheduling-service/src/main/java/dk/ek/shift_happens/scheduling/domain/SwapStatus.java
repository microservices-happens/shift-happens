package dk.ek.shift_happens.scheduling.domain;

/** Lifecycle of a swap request (contracts/events/payloads/swap.schema.json). */
public enum SwapStatus {
    PENDING,
    APPROVED,
    REJECTED;

    public boolean isPending() {
        return this == PENDING;
    }
}
