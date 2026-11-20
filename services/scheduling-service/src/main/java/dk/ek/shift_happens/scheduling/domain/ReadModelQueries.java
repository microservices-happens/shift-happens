package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read port of the Mongo read model (P2.10), shaped for the GraphQL queries of
 * contracts/graphql/scheduling.graphql. Every method returns stored state only; visibility rules are
 * applied by the use case in the application layer.
 */
public interface ReadModelQueries {

    /** Includes tombstoned shifts, so the caller decides whether it may show them. */
    Optional<ShiftView> findShift(long shiftId);

    List<ShiftView> findShifts(Instant from, Instant to, Long departmentId, Long workLocationId,
                               ShiftStatus status);

    /** Shifts inside the window that have an assignment for that employee. */
    List<ShiftView> findShiftsForEmployee(long employeeId, Instant from, Instant to);

    List<AssignmentView> findAssignmentsOfShift(long shiftId);

    List<AssignmentView> findAssignmentsOfEmployee(long employeeId, Instant from, Instant to);

    Optional<AssignmentView> findAssignment(long shiftAssignmentId);

    Optional<EmployeeView> findEmployee(long employeeId);

    List<EmployeeView> findEmployees(Collection<Long> employeeIds);

    List<SwapView> findSwaps(SwapStatus status);

    List<SwapView> findSwapsOfEmployee(long employeeId);

    List<SuggestionView> findSuggestions(long shiftId);
}
