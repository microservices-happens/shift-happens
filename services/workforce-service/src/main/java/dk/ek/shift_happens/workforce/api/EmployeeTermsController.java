package dk.ek.shift_happens.workforce.api;

import dk.ek.shift_happens.workforce.api.dto.EmployeeContract;
import dk.ek.shift_happens.workforce.api.dto.EmployeeJobRole;
import dk.ek.shift_happens.workforce.application.EmployeeNotFoundException;
import dk.ek.shift_happens.workforce.application.EmployeeTermsService;
import dk.ek.shift_happens.workforce.application.IdempotencyService;
import dk.ek.shift_happens.workforce.infrastructure.persistence.EmployeeTermsRepository;
import jakarta.validation.Valid;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/v1")
class EmployeeTermsController {

    private final EmployeeTermsRepository terms;
    private final EmployeeTermsService commands;
    private final IdempotencyService idempotency;

    EmployeeTermsController(EmployeeTermsRepository terms, EmployeeTermsService commands,
                            IdempotencyService idempotency) {
        this.terms = terms;
        this.commands = commands;
        this.idempotency = idempotency;
    }

    @GetMapping("/employeecontracts")
    List<EmployeeContract> listEmployeeContracts() {
        return terms.listEmployeeContracts();
    }

    @GetMapping("/employeecontracts/{id}")
    EmployeeContract getEmployeeContract(@PathVariable long id) {
        return terms.getEmployeeContract(id);
    }

    @PostMapping("/employeecontracts")
    ResponseEntity<?> createEmployeeContract(@RequestHeader("Idempotency-Key") String key,
                                     @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
                                     @Valid @RequestBody EmployeeContract request) {
        return idempotency.execute(key, "employeecontracts", request,
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(commands.createEmployeeContract(request, correlationId)));
    }

    @PutMapping("/employeecontracts/{id}")
    EmployeeContract updateEmployeeContract(@PathVariable long id,
                            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
                            @Valid @RequestBody EmployeeContract request) {
        return commands.updateEmployeeContract(id, request, correlationId);
    }

    @DeleteMapping("/employeecontracts/{id}")
    ResponseEntity<Void> deleteEmployeeContract(@PathVariable long id,
                                       @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        commands.deleteEmployeeContract(id, correlationId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/employeejobroles")
    List<EmployeeJobRole> listEmployeeJobRoles() {
        return terms.listEmployeeJobRoles();
    }

    @GetMapping("/employeejobroles/{id}")
    EmployeeJobRole getEmployeeJobRole(@PathVariable long id) {
        return terms.getEmployeeJobRole(id);
    }

    @PostMapping("/employeejobroles")
    ResponseEntity<?> createEmployeeJobRole(@RequestHeader("Idempotency-Key") String key,
                                     @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
                                     @Valid @RequestBody EmployeeJobRole request) {
        return idempotency.execute(key, "employeejobroles", request,
                () -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(commands.createEmployeeJobRole(request, correlationId)));
    }

    @PutMapping("/employeejobroles/{id}")
    EmployeeJobRole updateEmployeeJobRole(@PathVariable long id,
                            @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId,
                            @Valid @RequestBody EmployeeJobRole request) {
        return commands.updateEmployeeJobRole(id, request, correlationId);
    }

    @DeleteMapping("/employeejobroles/{id}")
    ResponseEntity<Void> deleteEmployeeJobRole(@PathVariable long id,
                                       @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        commands.deleteEmployeeJobRole(id, correlationId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler({DataIntegrityViolationException.class, EmployeeNotFoundException.class})
    ProblemDetail conflict() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The referenced employee, department or job role is unavailable, or the job role is already assigned");
    }
}
