package dk.ek.shift_happens.scheduling.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * The subset of workforce.employee.created/updated.v1 that Scheduling stores, matching
 * contracts/events/payloads/employee.schema.json. Never contains salary or credentials.
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
        List<Long> jobRoleIds) {
}
