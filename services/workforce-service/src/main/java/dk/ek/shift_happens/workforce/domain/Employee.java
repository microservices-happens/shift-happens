package dk.ek.shift_happens.workforce.domain;

import java.time.LocalDate;
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
        requireText(newEmployee.employeeNumber(), "employeeNumber");
        requireText(newEmployee.firstName(), "firstName");
        requireText(newEmployee.lastName(), "lastName");
        requireText(newEmployee.email(), "email");
        requireText(newEmployee.employmentStatus(), "employmentStatus");
        if (!ROLES.contains(newEmployee.userRole())) {
            throw new IllegalArgumentException("userRole must be one of " + ROLES);
        }
        return new Employee(
                null,
                newEmployee.employeeNumber(),
                newEmployee.userRole(),
                newEmployee.firstName(),
                newEmployee.lastName(),
                newEmployee.email(),
                newEmployee.phoneNumber(),
                newEmployee.hireDate(),
                newEmployee.employmentStatus(),
                newEmployee.primaryWorkLocationId(),
                1);
    }

    /** The full snapshot that goes into workforce.employee.created.v1 / .updated.v1. */
    public EmployeeSnapshot snapshot() {
        return new EmployeeSnapshot(
                id, employeeNumber, userRole, firstName, lastName, email,
                phoneNumber, hireDate, employmentStatus, primaryWorkLocationId,
                // Employee job roles are a separate slice (P2.5); the schema allows an empty list.
                java.util.List.of());
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
