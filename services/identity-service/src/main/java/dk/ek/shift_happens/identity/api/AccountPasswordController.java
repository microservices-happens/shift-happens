package dk.ek.shift_happens.identity.api;

import dk.ek.shift_happens.identity.application.SetPasswordService;
import dk.ek.shift_happens.identity.domain.Caller;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccountPasswordController {

    private static final String ADMINISTRATOR_AUTHORITY = "ROLE_Administrator";

    private final SetPasswordService setPasswordService;

    AccountPasswordController(SetPasswordService setPasswordService) {
        this.setPasswordService = setPasswordService;
    }

    @PutMapping("/v1/auth/accounts/{employeeId}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void setPassword(@PathVariable long employeeId, @Valid @RequestBody SetPasswordRequest request,
                     JwtAuthenticationToken authentication) {
        Number callerId = authentication.getToken().getClaim("employeeId");
        boolean administrator = authentication.getAuthorities().stream()
                .anyMatch(authority -> ADMINISTRATOR_AUTHORITY.equals(authority.getAuthority()));
        // A token without employeeId can only act as an admin; -1 never matches a real employee.
        Caller caller = new Caller(callerId == null ? -1 : callerId.longValue(), administrator);

        setPasswordService.setPassword(caller, employeeId, request.password());
    }
}
