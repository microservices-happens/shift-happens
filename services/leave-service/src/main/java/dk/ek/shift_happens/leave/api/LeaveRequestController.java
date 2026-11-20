package dk.ek.shift_happens.leave.api;

import dk.ek.shift_happens.leave.api.dto.CreateLeaveRequest;
import dk.ek.shift_happens.leave.api.dto.LeaveRequestResponse;
import dk.ek.shift_happens.leave.api.dto.UpdateLeaveRequest;
import dk.ek.shift_happens.leave.application.IdempotencyService;
import dk.ek.shift_happens.leave.application.LeaveRequestService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leave requests, from contracts/openapi/leave.yaml. Writes only: reads belong to the query role (#46).
 * Only POST takes an Idempotency-Key, as in the contract (scheduling's PUT does not either).
 */
@RestController
@RequestMapping("/v1/leaverequests")
@Profile("command")
public class LeaveRequestController {

    private final LeaveRequestService requests;
    private final IdempotencyService idempotency;

    LeaveRequestController(LeaveRequestService requests, IdempotencyService idempotency) {
        this.requests = requests;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateLeaveRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
            @AuthenticationPrincipal Jwt jwt) {

        requireSelfForEmployee(jwt, request.employeeId());
        return idempotency.execute(idempotencyKey, request, () -> ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(LeaveRequestResponse.from(requests.submit(request.employeeId(), request.leaveTypeId(),
                        request.startDate(), request.endDate(), request.reason(), correlationId))));
    }

    /** contracts/README.md: an Employee may submit only their own request (employeeId = JWT employeeId). */
    private static void requireSelfForEmployee(Jwt jwt, long employeeId) {
        if (jwt == null || !"Employee".equals(jwt.getClaimAsString("role"))) {
            return;
        }
        Object caller = jwt.getClaim("employeeId");
        long callerId = caller instanceof Number number ? number.longValue()
                : Long.parseLong(String.valueOf(caller));
        if (callerId != employeeId) {
            throw new AccessDeniedException("An Employee can only create their own leave request");
        }
    }

    @PatchMapping("/{id}")
    public ResponseEntity<LeaveRequestResponse> update(
            @PathVariable long id,
            @Valid @RequestBody UpdateLeaveRequest request,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(LeaveRequestResponse.from(
                requests.edit(id, request.leaveTypeId(), request.startDate(), request.endDate(), request.reason(),
                        correlationId)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(
            @PathVariable long id,
            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {

        requests.cancel(id, correlationId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
