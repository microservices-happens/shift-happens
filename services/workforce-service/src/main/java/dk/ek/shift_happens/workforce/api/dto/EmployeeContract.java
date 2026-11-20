package dk.ek.shift_happens.workforce.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.LocalDate;

public record EmployeeContract(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Long contractId,
        @NotNull @Positive Long employeeId,
        @NotNull @Positive Long departmentId,
        @NotBlank String contractType,
        @NotNull LocalDate startDate,
        LocalDate endDate,
        @PositiveOrZero Integer weeklyHours,
        Boolean isActive) {
}
