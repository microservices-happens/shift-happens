package dk.ek.shift_happens.notification.api;

import dk.ek.shift_happens.notification.application.NotificationQueryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.net.URI;
import java.util.List;

/**
 * The REST API of contracts/openapi/notification.yaml: the caller's own notifications only.
 *
 * <p>The employee id always comes from the validated JWT, never from the URL, so one employee cannot read
 * or acknowledge another employee's notifications. A manager's or administrator's notifications are
 * still only their own; the business alerts they receive are addressed to them by id.
 */
@RestController
@RequestMapping("/v1/notifications")
class NotificationController {

    private final NotificationQueryService service;

    NotificationController(NotificationQueryService service) {
        this.service = service;
    }

    @GetMapping
    List<NotificationResponse> myNotifications(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(defaultValue = "false") boolean unreadOnly) {
        return service.findFor(caller(jwt), unreadOnly).stream().map(NotificationResponse::of).toList();
    }

    @PatchMapping("/{id}")
    NotificationResponse markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
                                  @Valid @RequestBody NotificationUpdate update) {
        return NotificationResponse.of(service.setRead(id, caller(jwt), update.read()));
    }

    /** employeeId is a number in the contract; the claim is an Integer/Long/String depending on the issuer. */
    static long caller(Jwt jwt) {
        Object claim = jwt.getClaim("employeeId");
        if (claim instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(claim));
        } catch (NumberFormatException missing) {
            throw new IllegalArgumentException("The token has no employeeId claim");
        }
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    ProblemDetail notFound(NotificationNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage());
    }

    @ExceptionHandler(NotTheOwnerException.class)
    ProblemDetail forbidden(NotTheOwnerException e) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .findFirst().orElse("Invalid request");
        return problem(HttpStatus.BAD_REQUEST, "bad-request", detail);
    }

    private static ProblemDetail problem(HttpStatus status, String type, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("https://shift-happens.local/problems/" + type));
        return problem;
    }

    /** Body of PATCH /v1/notifications/{id} (schema NotificationUpdate). */
    record NotificationUpdate(@NotNull Boolean read) {
    }

    /** Response schema Notification. */
    record NotificationResponse(long notificationId, long employeeId, String title, String message,
                                String sourceEventType, java.util.UUID sourceEventId,
                                java.time.Instant createdAt, boolean read) {
        static NotificationResponse of(dk.ek.shift_happens.notification.domain.StoredNotification n) {
            return new NotificationResponse(n.notificationId(), n.employeeId(), n.title(), n.message(),
                    n.sourceEventType(), n.sourceEventId(), n.createdAt(), n.read());
        }
    }
}
