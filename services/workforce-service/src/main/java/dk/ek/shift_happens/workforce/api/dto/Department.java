package dk.ek.shift_happens.workforce.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record Department(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Long departmentId,
        @NotBlank String departmentName,
        Boolean isActive) {
}
