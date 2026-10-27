package dk.ek.shift_happens.workforce.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code data} of workforce.employee.created.v1 / .updated.v1, matching
 * contracts/events/payloads/employee.schema.json. Never contains passwords or salary.
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
        List<Integer> jobRoleIds) {
}
