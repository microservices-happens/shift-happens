package dk.ek.shift_happens.scheduling.application;

/** Thrown when a shift id does not exist, or the shift is soft-deleted. Maps to 404. */
public class ShiftNotFoundException extends RuntimeException {

    public ShiftNotFoundException(long shiftId) {
        super("no shift with id " + shiftId);
    }
}
