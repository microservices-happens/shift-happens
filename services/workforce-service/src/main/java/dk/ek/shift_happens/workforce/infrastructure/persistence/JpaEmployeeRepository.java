package dk.ek.shift_happens.workforce.infrastructure.persistence;

import dk.ek.shift_happens.workforce.domain.Employee;
import dk.ek.shift_happens.workforce.domain.EmployeeRepository;
import org.springframework.stereotype.Repository;

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
        return jpa.save(EmployeeEntity.from(employee)).toDomain();
    }

    @Override
    public Optional<Employee> findById(long employeeId) {
        return jpa.findById(employeeId).map(EmployeeEntity::toDomain);
    }
}
