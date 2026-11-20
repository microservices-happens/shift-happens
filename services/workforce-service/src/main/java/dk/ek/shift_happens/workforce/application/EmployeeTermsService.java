package dk.ek.shift_happens.workforce.application;

import dk.ek.shift_happens.workforce.api.dto.EmployeeContract;
import dk.ek.shift_happens.workforce.api.dto.EmployeeJobRole;
import dk.ek.shift_happens.workforce.domain.EmployeeJobRoleDates;
import dk.ek.shift_happens.workforce.infrastructure.persistence.EmployeeTermsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.stream.LongStream;

@Service
public class EmployeeTermsService {

    private final EmployeeTermsRepository terms;
    private final EmployeeService employees;

    EmployeeTermsService(EmployeeTermsRepository terms, EmployeeService employees) {
        this.terms = terms;
        this.employees = employees;
    }

    @Transactional
    public EmployeeContract createEmployeeContract(EmployeeContract request, String correlationId) {
        validate(request);
        long employeeId = request.employeeId();
        employees.lockEmployee(employeeId);
        EmployeeContract created = terms.createEmployeeContract(request);
        employees.publishTermsUpdate(employeeId, correlationId);
        return created;
    }

    @Transactional
    public EmployeeContract updateEmployeeContract(long id, EmployeeContract request, String correlationId) {
        validate(request);
        long previousEmployeeId = terms.lockEmployeeContract(id).employeeId();
        long employeeId = request.employeeId();
        long[] affected = LongStream.of(previousEmployeeId, employeeId).distinct().sorted().toArray();
        for (long affectedId : affected) {
            employees.lockEmployee(affectedId);
        }
        EmployeeContract updated = terms.updateEmployeeContract(id, request);
        for (long affectedId : affected) {
            employees.publishTermsUpdate(affectedId, correlationId);
        }
        return updated;
    }

    @Transactional
    public void deleteEmployeeContract(long id, String correlationId) {
        long employeeId = terms.lockEmployeeContract(id).employeeId();
        boolean active = employees.lockActiveEmployee(employeeId).isPresent();
        terms.deleteEmployeeContract(id);
        if (active) {
            employees.publishTermsUpdate(employeeId, correlationId);
        }
    }

    @Transactional
    public EmployeeJobRole createEmployeeJobRole(EmployeeJobRole request, String correlationId) {
        validate(request);
        long employeeId = request.employeeId();
        employees.lockEmployee(employeeId);
        EmployeeJobRole created = terms.createEmployeeJobRole(request);
        employees.publishTermsUpdate(employeeId, correlationId);
        return created;
    }

    @Transactional
    public EmployeeJobRole updateEmployeeJobRole(long id, EmployeeJobRole request, String correlationId) {
        validate(request);
        long previousEmployeeId = terms.lockEmployeeJobRole(id).employeeId();
        long employeeId = request.employeeId();
        long[] affected = LongStream.of(previousEmployeeId, employeeId).distinct().sorted().toArray();
        for (long affectedId : affected) {
            employees.lockEmployee(affectedId);
        }
        EmployeeJobRole updated = terms.updateEmployeeJobRole(id, request);
        for (long affectedId : affected) {
            employees.publishTermsUpdate(affectedId, correlationId);
        }
        return updated;
    }

    @Transactional
    public void deleteEmployeeJobRole(long id, String correlationId) {
        long employeeId = terms.lockEmployeeJobRole(id).employeeId();
        boolean active = employees.lockActiveEmployee(employeeId).isPresent();
        terms.deleteEmployeeJobRole(id);
        if (active) {
            employees.publishTermsUpdate(employeeId, correlationId);
        }
    }

    private static void validate(EmployeeContract request) {
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
    }

    private static void validate(EmployeeJobRole request) {
        EmployeeJobRoleDates.validate(request.assignedDate(), request.expiryDate(), LocalDate.now(ZoneOffset.UTC));
    }
}
