package dk.ek.shift_happens.workforce.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface EmployeeJpaRepository extends JpaRepository<EmployeeEntity, Long> {
}
