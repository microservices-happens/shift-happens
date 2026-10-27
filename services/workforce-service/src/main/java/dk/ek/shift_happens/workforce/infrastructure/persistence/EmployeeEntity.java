package dk.ek.shift_happens.workforce.infrastructure.persistence;

import dk.ek.shift_happens.workforce.domain.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/** JPA mapping for the employee table (V1__init.sql). The domain type stays framework-free. */
@Entity
@Table(name = "employee")
public class EmployeeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "employee_number", nullable = false, unique = true, length = 50)
    private String employeeNumber;

    @Column(name = "user_role", nullable = false, length = 20)
    private String userRole;

    @Column(name = "first_name", nullable = false, length = 100)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 100)
    private String lastName;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    @Column(name = "phone_number", length = 50)
    private String phoneNumber;

    @Column(name = "hire_date")
    private LocalDate hireDate;

    @Column(name = "employment_status", nullable = false, length = 50)
    private String employmentStatus;

    @Column(name = "primary_work_location_id")
    private Long primaryWorkLocationId;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "deleted", nullable = false)
    private boolean deleted;

    protected EmployeeEntity() {
        // for JPA
    }

    static EmployeeEntity from(Employee employee) {
        EmployeeEntity entity = new EmployeeEntity();
        entity.employeeId = employee.id();
        entity.employeeNumber = employee.employeeNumber();
        entity.userRole = employee.userRole();
        entity.firstName = employee.firstName();
        entity.lastName = employee.lastName();
        entity.email = employee.email();
        entity.phoneNumber = employee.phoneNumber();
        entity.hireDate = employee.hireDate();
        entity.employmentStatus = employee.employmentStatus();
        entity.primaryWorkLocationId = employee.primaryWorkLocationId();
        entity.aggregateVersion = employee.aggregateVersion();
        entity.deleted = false;
        return entity;
    }

    Employee toDomain() {
        return new Employee(employeeId, employeeNumber, userRole, firstName, lastName, email, phoneNumber,
                hireDate, employmentStatus, primaryWorkLocationId, aggregateVersion);
    }
}
