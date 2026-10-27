package dk.ek.shift_happens.workforce.api.dto;

import dk.ek.shift_happens.workforce.domain.NewEmployee;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/** Request body of POST /v1/employees (contracts/openapi/workforce.yaml, schema Employee). */
public record CreateEmployeeRequest(
        @NotBlank String employeeNumber,
        @NotBlank @Pattern(regexp = "Administrator|Manager|Employee",
                message = "must be Administrator, Manager or Employee") String userRole,
        @NotBlank String firstName,
        @NotBlank String lastName,
        @NotBlank @Email String email,
        String phoneNumber,
        LocalDate hireDate,
        @NotBlank String employmentStatus,
        Long primaryWorkLocationId) {

    public NewEmployee toNewEmployee() {
        return new NewEmployee(employeeNumber, userRole, firstName, lastName, email, phoneNumber, hireDate,
                employmentStatus, primaryWorkLocationId);
    }
}
