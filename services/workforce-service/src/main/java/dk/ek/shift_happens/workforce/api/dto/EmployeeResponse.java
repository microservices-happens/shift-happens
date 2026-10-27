package dk.ek.shift_happens.workforce.api.dto;

import dk.ek.shift_happens.workforce.domain.Employee;

import java.time.LocalDate;

/** Response body of the employee endpoints. Never contains salary or credentials. */
public record EmployeeResponse(
        Long employeeId,
        String employeeNumber,
        String userRole,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        LocalDate hireDate,
        String employmentStatus,
        Long primaryWorkLocationId) {

    public static EmployeeResponse from(Employee employee) {
        return new EmployeeResponse(
                employee.id(), employee.employeeNumber(), employee.userRole(), employee.firstName(),
                employee.lastName(), employee.email(), employee.phoneNumber(), employee.hireDate(),
                employee.employmentStatus(), employee.primaryWorkLocationId());
    }
}
