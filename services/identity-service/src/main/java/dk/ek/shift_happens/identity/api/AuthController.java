package dk.ek.shift_happens.identity.api;

import dk.ek.shift_happens.identity.application.LoginService;
import dk.ek.shift_happens.identity.domain.Account;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/auth")
class AuthController {

    private final LoginService loginService;

    AuthController(LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody LoginRequest request) {
        LoginService.LoginResult result = loginService.login(request.email(), request.password());
        Account account = result.account();
        return new LoginResponse(result.token(), account.employeeId(), account.employeeNumber(),
                account.firstName(), account.lastName(), account.email(), account.role());
    }
}
