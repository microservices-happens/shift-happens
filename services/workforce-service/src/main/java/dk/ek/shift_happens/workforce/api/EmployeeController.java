package dk.ek.shift_happens.workforce.api;

import dk.ek.shift_happens.workforce.api.dto.CreateEmployeeRequest;
import dk.ek.shift_happens.workforce.api.dto.EmployeeResponse;
import dk.ek.shift_happens.workforce.application.EmployeeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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

    EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EmployeeResponse create(@Valid @RequestBody CreateEmployeeRequest request,
                                   @RequestHeader(name = "X-Correlation-Id", required = false) String correlationId) {
        return EmployeeResponse.from(employees.create(request.toNewEmployee(), correlationId));
    }

    @GetMapping("/{employeeId}")
    public EmployeeResponse get(@PathVariable long employeeId) {
        return EmployeeResponse.from(employees.get(employeeId));
    }
}
