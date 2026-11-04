package dk.ek.shift_happens.scheduling.application;

/** Thrown when a swap id does not exist. Maps to 404. */
public class SwapNotFoundException extends RuntimeException {

    public SwapNotFoundException(long shiftSwapId) {
        super("no swap with id " + shiftSwapId);
    }
}
