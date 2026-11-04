package dk.ek.shift_happens.scheduling.domain;

import java.util.List;
import java.util.Optional;

/** Port for the employee replica. The adapter lives in infrastructure/persistence. */
public interface EmployeeReplicaRepository {

    Optional<EmployeeReplica> findByEmployeeId(long employeeId);

    /** The replica is small and local; the understaffed candidates need all of it. */
    List<EmployeeReplica> findAll();

    EmployeeReplica save(EmployeeReplica employee);
}
