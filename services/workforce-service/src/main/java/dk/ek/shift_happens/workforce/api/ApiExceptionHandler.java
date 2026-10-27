package dk.ek.shift_happens.workforce.api;

import dk.ek.shift_happens.workforce.application.EmployeeNotFoundException;
import dk.ek.shift_happens.workforce.application.IdempotencyConflictException;
import dk.ek.shift_happens.workforce.application.InvalidIdempotencyKeyException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/** Errors are RFC 9457 problem+json, as required by contracts/README.md. */
@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(EmployeeNotFoundException.class)
    ProblemDetail notFound(EmployeeNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Employee not found");
        problem.setType(URI.create("https://shift-happens.local/problems/employee-not-found"));
        return problem;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "employeeNumber or email already exists");
        problem.setTitle("Employee already exists");
        problem.setType(URI.create("https://shift-happens.local/problems/duplicate-employee"));
        return problem;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Idempotency-Key reused with a different request");
        problem.setType(URI.create("https://shift-happens.local/problems/idempotency-conflict"));
        return problem;
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail invalidIdempotencyKey(InvalidIdempotencyKeyException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid Idempotency-Key");
        problem.setType(URI.create("https://shift-happens.local/problems/invalid-idempotency-key"));
        return problem;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingHeader(MissingRequestHeaderException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                exception.getHeaderName() + " header is required");
        problem.setTitle("Required header is missing");
        problem.setType(URI.create("https://shift-happens.local/problems/missing-header"));
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid employee");
        problem.setType(URI.create("https://shift-happens.local/problems/invalid-employee"));
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
}
