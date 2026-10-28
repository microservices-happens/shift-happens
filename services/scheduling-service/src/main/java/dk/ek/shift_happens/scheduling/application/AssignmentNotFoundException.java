package dk.ek.shift_happens.scheduling.application;

/** Thrown when an assignment id does not exist, or the assignment is soft-deleted. Maps to 404. */
public class AssignmentNotFoundException extends RuntimeException {

    public AssignmentNotFoundException(long shiftAssignmentId) {
        super("no assignment with id " + shiftAssignmentId);
    }
}
