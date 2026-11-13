package dk.ek.shift_happens.workforce.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record JobRole(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Long jobRoleId,
        @NotBlank String roleName,
        String jobRoleDescription,
        Boolean isCertificationRequired) {
}
