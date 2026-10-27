package dk.ek.shift_happens.workforce.domain;

import java.time.LocalDate;

/** Input for creating an employee; a record so the API, the domain and the tests share one shape. */
public record NewEmployee(
        String employeeNumber,
        String userRole,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        LocalDate hireDate,
        String employmentStatus,
        Long primaryWorkLocationId) {
}
