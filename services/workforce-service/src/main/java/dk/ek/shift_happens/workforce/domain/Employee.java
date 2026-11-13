package dk.ek.shift_happens.workforce.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * An employee as Workforce owns it. Plain Java: no Spring, no JPA.
 *
 * <p>Salary and passwords are deliberately not part of this aggregate; the contract says sensitive
 * fields never leave the service (contracts/openapi/workforce.yaml).
 */
public record Employee(
        Long id,
        String employeeNumber,
        String userRole,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        LocalDate hireDate,
        String employmentStatus,
        Long primaryWorkLocationId,
        long aggregateVersion) {

    public static final Set<String> ROLES = Set.of("Administrator", "Manager", "Employee");

    /** Creates a new employee; the id and the first aggregate version are assigned on save. */
    public static Employee create(NewEmployee newEmployee) {
        return withDetails(null, 1, newEmployee);
    }

    public Employee update(NewEmployee details) {
        return withDetails(id, aggregateVersion + 1, details);
    }

    private static Employee withDetails(Long id, long version, NewEmployee newEmployee) {
        requireText(newEmployee.employeeNumber(), "employeeNumber");
        requireText(newEmployee.firstName(), "firstName");
        requireText(newEmployee.lastName(), "lastName");
        requireText(newEmployee.email(), "email");
        requireText(newEmployee.employmentStatus(), "employmentStatus");
        if (!ROLES.contains(newEmployee.userRole())) {
            throw new IllegalArgumentException("userRole must be one of " + ROLES);
        }
        return new Employee(
                id,
                newEmployee.employeeNumber(),
                newEmployee.userRole(),
                newEmployee.firstName(),
                newEmployee.lastName(),
                newEmployee.email(),
                newEmployee.phoneNumber(),
                newEmployee.hireDate(),
                newEmployee.employmentStatus(),
                newEmployee.primaryWorkLocationId(),
                version);
    }

    /** The full snapshot that goes into workforce.employee.created.v1 / .updated.v1. */
    public EmployeeSnapshot snapshot(List<Long> jobRoleIds, int weeklyContractHours) {
        return new EmployeeSnapshot(
                id, employeeNumber, userRole, firstName, lastName, email,
                phoneNumber, hireDate, employmentStatus, primaryWorkLocationId,
                jobRoleIds, weeklyContractHours);
    }

    public Employee nextVersion() {
        return new Employee(id, employeeNumber, userRole, firstName, lastName, email, phoneNumber,
                hireDate, employmentStatus, primaryWorkLocationId, aggregateVersion + 1);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
