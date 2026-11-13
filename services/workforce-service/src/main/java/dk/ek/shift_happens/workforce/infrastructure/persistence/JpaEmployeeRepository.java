package dk.ek.shift_happens.workforce.infrastructure.persistence;

import dk.ek.shift_happens.workforce.domain.Employee;
import dk.ek.shift_happens.workforce.domain.EmployeeFilter;
import dk.ek.shift_happens.workforce.domain.EmployeeRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** Adapter: implements the domain port on top of Spring Data JPA. */
@Repository
class JpaEmployeeRepository implements EmployeeRepository {

    private final EmployeeJpaRepository jpa;

    JpaEmployeeRepository(EmployeeJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Employee save(Employee employee) {
        return jpa.saveAndFlush(EmployeeEntity.from(employee)).toDomain();
    }

    @Override
    public Optional<Employee> findById(long employeeId) {
        return jpa.findByEmployeeIdAndDeletedFalse(employeeId).map(EmployeeEntity::toDomain);
    }

    @Override
    public Optional<Employee> lockById(long employeeId) {
        return jpa.lockActiveById(employeeId).map(EmployeeEntity::toDomain);
    }

    @Override
    public void softDelete(long employeeId, long aggregateVersion) {
        jpa.softDelete(employeeId, aggregateVersion);
    }

    @Override
    public List<Employee> findAll(EmployeeFilter filter) {
        return jpa.findActive(filter.employmentStatus(), filter.primaryWorkLocationId(), filter.userRole(),
                filter.email(), filter.firstName(), filter.lastName())
                .stream().map(EmployeeEntity::toDomain).toList();
    }
}
