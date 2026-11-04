package dk.ek.shift_happens.identity.api;

import dk.ek.shift_happens.identity.application.InvalidCredentialsException;
import dk.ek.shift_happens.identity.application.OidcProviderUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Errors are RFC 9457 problem+json, as required by contracts/README.md. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail invalidCredentials(InvalidCredentialsException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
        problem.setTitle("Login failed");
        problem.setType(URI.create("https://shift-happens.local/problems/invalid-credentials"));
        return problem;
    }

    @ExceptionHandler(OidcProviderUnavailableException.class)
    ProblemDetail oidcProviderUnavailable(OidcProviderUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Unknown or disabled login provider");
        problem.setType(URI.create("https://shift-happens.local/problems/oidc-provider-unavailable"));
        return problem;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidBody(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> errors.put(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Validation failed");
        problem.setTitle("Invalid request body");
        problem.setType(URI.create("https://shift-happens.local/problems/validation"));
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body is not valid JSON");
        problem.setTitle("Invalid request body");
        problem.setType(URI.create("https://shift-happens.local/problems/validation"));
        return problem;
    }
}
