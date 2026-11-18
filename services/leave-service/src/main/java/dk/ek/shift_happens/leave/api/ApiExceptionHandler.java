package dk.ek.shift_happens.leave.api;

import dk.ek.shift_happens.leave.application.IdempotencyConflictException;
import dk.ek.shift_happens.leave.application.InvalidIdempotencyKeyException;
import dk.ek.shift_happens.leave.application.LeaveApprovalNotFoundException;
import dk.ek.shift_happens.leave.application.LeaveRequestNotFoundException;
import dk.ek.shift_happens.leave.application.LeaveTypeNotFoundException;
import dk.ek.shift_happens.leave.domain.ApprovalNotAvailableException;
import dk.ek.shift_happens.leave.domain.InsufficientLeaveBalanceException;
import dk.ek.shift_happens.leave.domain.InvalidLeaveRequestException;
import dk.ek.shift_happens.leave.domain.LeaveRequestRejectedException;
import dk.ek.shift_happens.leave.domain.LeaveTypeConflictException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
        problem.setTitle("Invalid request");
        problem.setType(URI.create("https://shift-happens.local/problems/invalid-request"));
        return problem;
    }

    @ExceptionHandler(InvalidLeaveRequestException.class)
    ProblemDetail invalidLeave(InvalidLeaveRequestException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid leave request", "invalid-leave-request",
                exception.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadableBody(HttpMessageNotReadableException exception) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request body", "validation",
                "The request body is missing or malformed (check dates as yyyy-MM-dd and enum values)");
    }

    @ExceptionHandler(LeaveTypeNotFoundException.class)
    ProblemDetail leaveTypeNotFound(LeaveTypeNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Leave type not found", "leave-type-not-found", exception.getMessage());
    }

    @ExceptionHandler(LeaveRequestNotFoundException.class)
    ProblemDetail leaveRequestNotFound(LeaveRequestNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Leave request not found", "leave-request-not-found",
                exception.getMessage());
    }

    @ExceptionHandler(LeaveApprovalNotFoundException.class)
    ProblemDetail leaveApprovalNotFound(LeaveApprovalNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "Leave approval not found", "leave-approval-not-found",
                exception.getMessage());
    }

    @ExceptionHandler(LeaveRequestRejectedException.class)
    ProblemDetail leaveRejected(LeaveRequestRejectedException exception) {
        return problem(HttpStatus.CONFLICT, "Leave request rejected", "leave-request-rejected",
                exception.getMessage());
    }

    @ExceptionHandler(InsufficientLeaveBalanceException.class)
    ProblemDetail insufficientBalance(InsufficientLeaveBalanceException exception) {
        return problem(HttpStatus.CONFLICT, "Insufficient leave balance", "insufficient-leave-balance",
                exception.getMessage());
    }

    @ExceptionHandler(LeaveTypeConflictException.class)
    ProblemDetail leaveTypeConflict(LeaveTypeConflictException exception) {
        return problem(HttpStatus.CONFLICT, "Leave type conflict", "leave-type-conflict", exception.getMessage());
    }

    @ExceptionHandler(ApprovalNotAvailableException.class)
    ProblemDetail approvalNotAvailable(ApprovalNotAvailableException exception) {
        return problem(HttpStatus.CONFLICT, "Approval not available yet", "approval-not-available",
                exception.getMessage());
    }

    /** The overlap exclusion constraint of V3 caught a concurrent request that the application check missed. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrityViolation(DataIntegrityViolationException exception) {
        return problem(HttpStatus.CONFLICT, "Conflicting change", "conflicting-change",
                "The change conflicts with a concurrent change (for example an overlapping leave request)");
    }

    /**
     * A lock could not be acquired or a deadlock was detected (CannotAcquireLock, PessimisticLockingFailure,
     * DeadlockLoser are all ConcurrencyFailureException): the change lost a race, retrying is safe.
     */
    @ExceptionHandler(ConcurrencyFailureException.class)
    ProblemDetail concurrencyFailure(ConcurrencyFailureException exception) {
        return problem(HttpStatus.CONFLICT, "Conflicting change", "conflicting-change",
                "The change collided with a concurrent change; please retry the request");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://shift-happens.local/problems/" + type));
        return problem;
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    ProblemDetail invalidIdempotencyKey(InvalidIdempotencyKeyException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid Idempotency-Key");
        problem.setType(URI.create("https://shift-happens.local/problems/invalid-idempotency-key"));
        return problem;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(IdempotencyConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Idempotency-Key reused with a different request");
        problem.setType(URI.create("https://shift-happens.local/problems/idempotency-conflict"));
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
