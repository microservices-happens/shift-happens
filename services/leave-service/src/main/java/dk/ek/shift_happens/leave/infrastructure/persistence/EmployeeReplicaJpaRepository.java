package dk.ek.shift_happens.leave.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data repository for the employee replica. */
interface EmployeeReplicaJpaRepository extends JpaRepository<EmployeeReplicaEntity, Long> {
}
