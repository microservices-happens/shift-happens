package dk.ek.shift_happens.leave.domain;

/**
 * The subset of workforce.employee.created/updated.v1 that Leave stores, matching
 * contracts/events/payloads/employee.schema.json. Never contains salary or credentials.
 */
public record EmployeeSnapshot(
        Long employeeId,
        String firstName,
        String lastName,
        String employmentStatus) {
}
