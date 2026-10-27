package dk.ek.shift_happens.workforce.domain;

import java.util.List;
import java.util.Optional;

/** Port: the application layer does not know that the data lives in PostgreSQL (infrastructure). */
public interface EmployeeRepository {

    Employee save(Employee employee);

    Optional<Employee> findById(long employeeId);

    List<Employee> findAll();
}
