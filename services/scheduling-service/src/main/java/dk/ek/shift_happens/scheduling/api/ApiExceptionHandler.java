package dk.ek.shift_happens.scheduling.api;

import dk.ek.shift_happens.scheduling.application.ApprovalNotFoundException;
import dk.ek.shift_happens.scheduling.application.AssignmentNotFoundException;
import dk.ek.shift_happens.scheduling.application.InvalidIdempotencyKeyException;
import dk.ek.shift_happens.scheduling.application.ShiftNotFoundException;
import dk.ek.shift_happens.scheduling.domain.AssignmentRejectedException;
import dk.ek.shift_happens.scheduling.domain.DuplicateRequiredJobRoleException;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleNotFoundException;
import dk.ek.shift_happens.scheduling.domain.ShiftNotEditableException;
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

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid shift");
        problem.setType(URI.create("https://shift-happens.local/problems/invalid-shift"));
        return problem;
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail invalidIdempotencyKey(InvalidIdempotencyKeyException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid Idempotency-Key");
        problem.setType(URI.create("https://shift-happens.local/problems/invalid-idempotency-key"));
        return problem;
    }

    @ExceptionHandler(AssignmentNotFoundException.class)
    ProblemDetail assignmentNotFound(AssignmentNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Assignment not found");
        problem.setType(URI.create("https://shift-happens.local/problems/assignment-not-found"));
        return problem;
    }

    @ExceptionHandler(ApprovalNotFoundException.class)
    ProblemDetail approvalNotFound(ApprovalNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Approval not found");
        problem.setType(URI.create("https://shift-happens.local/problems/approval-not-found"));
        return problem;
    }

    @ExceptionHandler(AssignmentRejectedException.class)
    ProblemDetail assignmentRejected(AssignmentRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Assignment rejected");
        problem.setType(URI.create("https://shift-happens.local/problems/assignment-rejected"));
        return problem;
    }

    @ExceptionHandler(RequiredJobRoleNotFoundException.class)
    ProblemDetail requiredJobRoleNotFound(RequiredJobRoleNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Required job role not found");
        problem.setType(URI.create("https://shift-happens.local/problems/required-job-role-not-found"));
        return problem;
    }

    @ExceptionHandler(DuplicateRequiredJobRoleException.class)
    ProblemDetail duplicateRequiredJobRole(DuplicateRequiredJobRoleException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Duplicate required job role");
        problem.setType(URI.create("https://shift-happens.local/problems/duplicate-required-job-role"));
        return problem;
    }

    @ExceptionHandler(ShiftNotFoundException.class)
    ProblemDetail notFound(ShiftNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Shift not found");
        problem.setType(URI.create("https://shift-happens.local/problems/shift-not-found"));
        return problem;
    }

    @ExceptionHandler(ShiftNotEditableException.class)
    ProblemDetail notEditable(ShiftNotEditableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Shift cannot be changed");
        problem.setType(URI.create("https://shift-happens.local/problems/shift-not-editable"));
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
