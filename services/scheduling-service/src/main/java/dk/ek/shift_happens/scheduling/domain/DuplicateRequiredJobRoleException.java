package dk.ek.shift_happens.scheduling.domain;

/** Thrown when a shift would need the same job role twice. Maps to 409 Conflict. */
public class DuplicateRequiredJobRoleException extends RuntimeException {

    public DuplicateRequiredJobRoleException(Long shiftId, long jobRoleId) {
        super("shift " + shiftId + " already requires job role " + jobRoleId);
    }
}
