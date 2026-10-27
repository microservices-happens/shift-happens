package dk.ek.shift_happens.workforce.application;

import dk.ek.shift_happens.workforce.domain.Employee;
import dk.ek.shift_happens.workforce.domain.EmployeeRepository;
import dk.ek.shift_happens.workforce.domain.NewEmployee;
import dk.ek.shift_happens.workforce.infrastructure.messaging.EventEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Use cases for employees. Transaction boundaries live here, nowhere else. */
@Service
public class EmployeeService {

    public static final String EMPLOYEE_CREATED = "workforce.employee.created.v1";

    private final EmployeeRepository employees;
    private final EventOutbox outbox;
    private final String producer;

    EmployeeService(EmployeeRepository employees, EventOutbox outbox,
                    @Value("${app.events.producer}") String producer) {
        this.employees = employees;
        this.outbox = outbox;
        this.producer = producer;
    }

    /**
     * Saves the employee and writes {@code workforce.employee.created.v1} to the outbox in the same
     * transaction, so the event cannot be lost and cannot exist without the employee.
     */
    @Transactional
    public Employee create(NewEmployee newEmployee, String correlationId) {
        Employee saved = employees.save(Employee.create(newEmployee));

        outbox.append(new EventEnvelope(
                UUID.randomUUID(),
                EMPLOYEE_CREATED,
                Instant.now(),
                producer,
                saved.id(),
                saved.aggregateVersion(),
                correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId,
                null,
                saved.snapshot()));

        return saved;
    }

    @Transactional(readOnly = true)
    public Employee get(long employeeId) {
        return employees.findById(employeeId).orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    }
}
