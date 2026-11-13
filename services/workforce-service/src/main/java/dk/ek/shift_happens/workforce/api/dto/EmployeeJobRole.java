package dk.ek.shift_happens.workforce.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDate;

public record EmployeeJobRole(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Long employeeJobRoleId,
        @NotNull @Positive Long employeeId,
        @NotNull @Positive Long jobRoleId,
        @NotNull LocalDate assignedDate,
        LocalDate expiryDate,
        String proficiencyLevel) {
}
