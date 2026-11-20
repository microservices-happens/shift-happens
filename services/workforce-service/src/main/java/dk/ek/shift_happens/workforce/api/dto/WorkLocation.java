package dk.ek.shift_happens.workforce.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

public record WorkLocation(
        @JsonProperty(access = JsonProperty.Access.READ_ONLY) Long workLocationId,
        @NotBlank String locationName,
        String addressLine1,
        String addressLine2,
        @NotBlank String city,
        @NotBlank String country,
        @NotBlank String timezone,
        Boolean isActive) {
}
