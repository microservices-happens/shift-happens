package dk.ek.shift_happens.scheduling.infrastructure.persistence;

import dk.ek.shift_happens.scheduling.domain.EmployeeReplica;
import dk.ek.shift_happens.scheduling.domain.EmployeeReplicaRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Adapter: the employee replica on top of Spring Data JPA. Command role only. */
@Repository
@Profile("command")
class JpaEmployeeReplicaRepository implements EmployeeReplicaRepository {

    private final EmployeeReplicaJpaRepository jpa;

    JpaEmployeeReplicaRepository(EmployeeReplicaJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Optional<EmployeeReplica> findByEmployeeId(long employeeId) {
        return jpa.findById(employeeId).map(EmployeeReplicaEntity::toDomain);
    }

    @Override
    public EmployeeReplica save(EmployeeReplica employee) {
        return jpa.save(EmployeeReplicaEntity.from(employee)).toDomain();
    }
}
