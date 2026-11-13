package dk.ek.shift_happens.workforce.api;

import dk.ek.shift_happens.workforce.api.dto.CreateEmployeeRequest;
import dk.ek.shift_happens.workforce.api.dto.EmployeeResponse;
import dk.ek.shift_happens.workforce.application.EmployeeService;
import dk.ek.shift_happens.workforce.application.IdempotencyService;
import dk.ek.shift_happens.workforce.domain.EmployeeFilter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API of contracts/openapi/workforce.yaml.
 *
 * <p>Security (JWT + Administrator/Manager roles for writes) is P1.7 and is added together with the
 * Identity service, because until Identity publishes its JWKS no token can be validated.
 */
@RestController
@RequestMapping("/v1/employees")
public class EmployeeController {

    private final EmployeeService employees;
    private final IdempotencyService idempotency;

    EmployeeController(EmployeeService employees, IdempotencyService idempotency) {
        this.employees = employees;
        this.idempotency = idempotency;
    }

    /** The Idempotency-Key header is required by the contract; a replay returns the stored response. */
    @PostMapping
    public ResponseEntity<?> create(@RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody CreateEmployeeRequest request,
                                    @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        return idempotency.execute(idempotencyKey, request,
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(EmployeeResponse.from(employees.create(request.toNewEmployee(), correlationId))));
    }

    @GetMapping
    public List<EmployeeResponse> list(
            @RequestParam(required = false) String employmentStatus,
            @RequestParam(required = false) Long primaryWorkLocationId,
            @RequestParam(required = false) String userRole,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String firstName,
            @RequestParam(required = false) String lastName) {
        return employees.findAll(new EmployeeFilter(employmentStatus, primaryWorkLocationId, userRole,
                email, firstName, lastName)).stream().map(EmployeeResponse::from).toList();
    }

    @PatchMapping("/{id}")
    public EmployeeResponse update(@PathVariable long id, @Valid @RequestBody CreateEmployeeRequest request,
                                   @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        return EmployeeResponse.from(employees.update(id, request.toNewEmployee(), correlationId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id,
                                       @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        employees.delete(id, correlationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}")
    public EmployeeResponse get(@PathVariable long id) {
        return EmployeeResponse.from(employees.get(id));
    }
}
