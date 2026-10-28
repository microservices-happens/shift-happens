package dk.ek.shift_happens.scheduling.domain;

import java.util.Optional;

/** Port for the employee replica. The adapter lives in infrastructure/persistence. */
public interface EmployeeReplicaRepository {

    Optional<EmployeeReplica> findByEmployeeId(long employeeId);

    EmployeeReplica save(EmployeeReplica employee);
}
