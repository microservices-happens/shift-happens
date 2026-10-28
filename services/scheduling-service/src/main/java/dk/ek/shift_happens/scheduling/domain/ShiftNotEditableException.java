package dk.ek.shift_happens.scheduling.domain;

/**
 * Thrown when an update asks for a status change the shift may not make, for example
 * PUBLISHED back to PLANNED, or anything after CANCELLED. Maps to 409 Conflict.
 */
public class ShiftNotEditableException extends RuntimeException {

    public ShiftNotEditableException(Long shiftId, ShiftStatus current, ShiftStatus target) {
        super("shift " + shiftId + " cannot change status from " + current + " to " + target);
    }
}
