package dk.ek.shift_happens.scheduling.domain;

/** Thrown when an update asks for a status change the shift may not make. Maps to 409 Conflict. */
public class ShiftNotEditableException extends RuntimeException {

    public ShiftNotEditableException(Long shiftId, ShiftStatus current, ShiftStatus target) {
        super("shift " + shiftId + " cannot change status from " + current + " to " + target);
    }

    public ShiftNotEditableException(Long shiftId, String reason) {
        super("shift " + shiftId + " cannot be changed: " + reason);
    }
}
