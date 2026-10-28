package dk.ek.shift_happens.scheduling.domain;

/** Thrown when a shift does not have the required job role row the caller addressed. Maps to 404. */
public class RequiredJobRoleNotFoundException extends RuntimeException {

    public RequiredJobRoleNotFoundException(Long shiftId, long shiftRequiredJobRoleId) {
        super("shift " + shiftId + " has no required job role row " + shiftRequiredJobRoleId);
    }

    public RequiredJobRoleNotFoundException(long shiftRequiredJobRoleId) {
        super("no required job role row with id " + shiftRequiredJobRoleId);
    }
}
