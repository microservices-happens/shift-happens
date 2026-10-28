package dk.ek.shift_happens.scheduling.domain;

/** A business rule refused the assignment change (not qualified, overlap, cancelled shift). Maps to 409. */
public class AssignmentRejectedException extends RuntimeException {

    public AssignmentRejectedException(String reason) {
        super(reason);
    }
}
