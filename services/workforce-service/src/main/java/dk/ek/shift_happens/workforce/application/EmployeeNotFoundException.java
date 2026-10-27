package dk.ek.shift_happens.workforce.application;

/** The requested employee does not exist (mapped to 404 by the API layer). */
public class EmployeeNotFoundException extends RuntimeException {

    public EmployeeNotFoundException(long employeeId) {
        super("No employee with id " + employeeId);
    }
}
