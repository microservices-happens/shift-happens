package dk.ek.shift_happens.identity.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** SetPasswordRequest in contracts/openapi/identity.yaml. */
public record SetPasswordRequest(@NotNull @Size(min = 8) String password) {
}
