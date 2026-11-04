package dk.ek.shift_happens.scheduling.domain;

/** A swap rule refused the change (wrong owner, already decided, ineligible colleague). Maps to 409. */
public class SwapRejectedException extends RuntimeException {

    public SwapRejectedException(String reason) {
        super(reason);
    }
}
