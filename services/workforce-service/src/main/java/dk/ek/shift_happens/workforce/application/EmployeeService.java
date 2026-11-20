package dk.ek.shift_happens.workforce.application;

import dk.ek.shift_happens.workforce.domain.Employee;
import dk.ek.shift_happens.workforce.domain.EmployeeFilter;
import dk.ek.shift_happens.workforce.domain.EmployeeRepository;
import dk.ek.shift_happens.workforce.domain.EmployeeSnapshot;
import dk.ek.shift_happens.workforce.domain.NewEmployee;
import dk.ek.shift_happens.workforce.infrastructure.messaging.EventEnvelope;
import dk.ek.shift_happens.workforce.infrastructure.persistence.EmployeeTermsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Use cases for employees. Transaction boundaries live here, nowhere else. */
@Service
public class EmployeeService {

    public static final String EMPLOYEE_CREATED = "workforce.employee.created.v1";

    private static final String EMPLOYEE_UPDATED = "workforce.employee.updated.v1";
    private static final String EMPLOYEE_DELETED = "workforce.employee.deleted.v1";

    private final EmployeeRepository employees;
    private final EventOutbox outbox;
    private final EmployeeTermsRepository terms;
    private final String producer;

    EmployeeService(EmployeeRepository employees, EventOutbox outbox, EmployeeTermsRepository terms,
                    @Value("${app.events.producer}") String producer) {
        this.employees = employees;
        this.outbox = outbox;
        this.terms = terms;
        this.producer = producer;
    }

    /**
     * Saves the employee and writes {@code workforce.employee.created.v1} to the outbox in the same
     * transaction, so the event cannot be lost and cannot exist without the employee.
     */
    @Transactional
    public Employee create(NewEmployee newEmployee, String correlationId) {
        Employee saved = employees.save(Employee.create(newEmployee));

        publish(EMPLOYEE_CREATED, saved.id(), saved.aggregateVersion(), correlationId, Instant.now(),
                saved.snapshot(List.of(), 0));
        return saved;
    }

    @Transactional
    public Employee update(long employeeId, NewEmployee details, String correlationId) {
        Employee current = lockEmployee(employeeId);
        Employee saved = employees.save(current.update(details));
        publishUpdated(saved, correlationId);
        return saved;
    }

    @Transactional
    public void delete(long employeeId, String correlationId) {
        Employee current = lockEmployee(employeeId);
        long version = current.aggregateVersion() + 1;
        Instant deletedAt = Instant.now();
        employees.softDelete(employeeId, version);
        terms.clearRefresh(employeeId);
        publish(EMPLOYEE_DELETED, employeeId, version, correlationId, deletedAt,
                new EmployeeTombstone("employee", employeeId, deletedAt));
    }

    Employee lockEmployee(long employeeId) {
        return lockActiveEmployee(employeeId).orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    }

    Optional<Employee> lockActiveEmployee(long employeeId) {
        return employees.lockById(employeeId);
    }

    void publishTermsUpdate(long employeeId, String correlationId) {
        publishUpdated(employees.save(lockEmployee(employeeId).nextVersion()), correlationId);
    }

    private void publishUpdated(Employee employee, String correlationId) {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        publish(EMPLOYEE_UPDATED, employee.id(), employee.aggregateVersion(), correlationId, now,
                snapshotOn(employee, today));
        terms.reschedule(employee.id(), today);
    }

    @Transactional
    public void refreshTerms(long employeeId, LocalDate today) {
        var active = lockActiveEmployee(employeeId);
        if (active.isEmpty()) {
            terms.clearRefresh(employeeId);
            return;
        }
        var refresh = terms.dueRefresh(employeeId, today);
        if (refresh.isEmpty()) {
            return;
        }
        Employee employee = active.get();
        LocalDate evaluatedOn = refresh.get().evaluatedOn();
        EmployeeSnapshot current = snapshotOn(employee, today);
        if (evaluatedOn == null || !current.equals(snapshotOn(employee, evaluatedOn))) {
            Employee versioned = employees.save(employee.nextVersion());
            publish(EMPLOYEE_UPDATED, employeeId, versioned.aggregateVersion(), null, Instant.now(), current);
        }
        terms.reschedule(employeeId, today);
    }

    private EmployeeSnapshot snapshotOn(Employee employee, LocalDate date) {
        return employee.snapshot(terms.activeJobRoleIds(employee.id(), date),
                terms.weeklyContractHours(employee.id(), date));
    }

    private void publish(String eventType, long employeeId, long version, String correlationId,
                         Instant occurredAt, Object data) {
        outbox.append(new EventEnvelope(
                UUID.randomUUID(),
                eventType,
                occurredAt,
                producer,
                employeeId,
                version,
                correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId,
                null,
                data));
    }

    private record EmployeeTombstone(String entityType, long entityId, Instant deletedAt) {
    }

    @Transactional(readOnly = true)
    public Employee get(long employeeId) {
        return employees.findById(employeeId).orElseThrow(() -> new EmployeeNotFoundException(employeeId));
    }

    @Transactional(readOnly = true)
    public List<Employee> findAll(EmployeeFilter filter) {
        return employees.findAll(filter);
    }
}
