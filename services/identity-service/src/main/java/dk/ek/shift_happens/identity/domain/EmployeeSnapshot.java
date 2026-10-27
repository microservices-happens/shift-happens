package dk.ek.shift_happens.identity.domain;

import java.time.LocalDate;

/**
 * {@code data} of workforce.employee.created.v1 / .updated.v1 (contracts/events/payloads/employee.schema.json).
 * Identity only needs the fields that decide whether the account exists and can log in.
 */
public record EmployeeSnapshot(
        Long employeeId,
        String employeeNumber,
        String userRole,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        LocalDate hireDate,
        String employmentStatus,
        Long primaryWorkLocationId,
        java.util.List<Integer> jobRoleIds) {
}
