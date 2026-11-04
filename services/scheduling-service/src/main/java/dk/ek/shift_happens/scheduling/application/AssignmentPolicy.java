package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.domain.AssignmentRejectedException;
import dk.ek.shift_happens.scheduling.domain.EmployeeReplica;
import dk.ek.shift_happens.scheduling.domain.EmployeeReplicaRepository;
import dk.ek.shift_happens.scheduling.domain.Shift;
import dk.ek.shift_happens.scheduling.domain.ShiftAssignmentRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The rule "may this employee take this shift?", in one place because both creating an assignment and
 * naming the colleague in a swap have to ask it.
 *
 * <p>It reads local data only: the employee replica (contract feed) and this service's assignments.
 */
@Component
@Profile("command")
class AssignmentPolicy {

    private final EmployeeReplicaRepository employees;
    private final ShiftAssignmentRepository assignments;

    AssignmentPolicy(EmployeeReplicaRepository employees, ShiftAssignmentRepository assignments) {
        this.employees = employees;
        this.assignments = assignments;
    }

    /** Active, qualified (P2.8) and free at that time, on a shift that still takes staff. */
    void requireAssignable(Shift shift, long employeeId) {
        if (shift.status().isCancelled()) {
            throw new AssignmentRejectedException("shift " + shift.shiftId() + " is cancelled");
        }

        EmployeeReplica employee = employees.findByEmployeeId(employeeId)
                .orElseThrow(() -> new AssignmentRejectedException("unknown employee " + employeeId));
        if (!employee.isActive()) {
            throw new AssignmentRejectedException("employee " + employeeId + " is not active");
        }
        if (!shift.accepts(employee)) {
            throw new AssignmentRejectedException("employee " + employeeId
                    + " holds none of the job roles shift " + shift.shiftId() + " requires");
        }

        // Half-open intervals: an assignment ending exactly when the shift starts is fine.
        if (!assignments.findActiveOverlapping(employeeId, shift.startDatetime(), shift.endDatetime()).isEmpty()) {
            throw new AssignmentRejectedException("employee " + employeeId
                    + " already works a shift that overlaps " + shift.shiftName());
        }
    }
}
