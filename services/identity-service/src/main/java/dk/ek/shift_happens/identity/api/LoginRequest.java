package dk.ek.shift_happens.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** LoginRequest in contracts/openapi/identity.yaml. */
public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
}
