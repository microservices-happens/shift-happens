package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Read port of the Mongo read model (P2.10), shaped for the GraphQL queries of
 * contracts/graphql/scheduling.graphql. Every method returns stored state only; visibility rules are
 * applied by the use case in the application layer.
 *
 * <p>The methods that take a collection of ids are the batch forms of the single reads: one query
 * for any number of ids, and for each id exactly what the single read returns. The GraphQL resolvers use
 * them, so a nested query costs a fixed number of queries instead of one per parent.
 */
public interface ReadModelQueries {

    /** Includes tombstoned shifts, so the caller decides whether it may show them. */
    Optional<ShiftView> findShift(long shiftId);

    /** {@link #findShift} for many ids in one query, in no particular order; unknown ids are left out. */
    List<ShiftView> findShiftsByIds(Collection<Long> shiftIds);

    List<ShiftView> findShifts(Instant from, Instant to, Long departmentId, Long workLocationId,
                               ShiftStatus status);

    /** Shifts inside the window that have an assignment for that employee. */
    List<ShiftView> findShiftsForEmployee(long employeeId, Instant from, Instant to);

    List<AssignmentView> findAssignmentsOfShift(long shiftId);

    /**
     * {@link #findAssignmentsOfShift} for many shifts in one query. Ordered by assignment id, so the
     * assignments of one shift come in the order the single read gives them.
     */
    List<AssignmentView> findAssignmentsOfShifts(Collection<Long> shiftIds);

    List<AssignmentView> findAssignmentsOfEmployee(long employeeId, Instant from, Instant to);

    Optional<AssignmentView> findAssignment(long shiftAssignmentId);

    /** {@link #findAssignment} for many ids in one query, in no particular order. */
    List<AssignmentView> findAssignmentsByIds(Collection<Long> shiftAssignmentIds);

    Optional<EmployeeView> findEmployee(long employeeId);

    /** {@link #findEmployee} for many ids in one query: tombstoned employees are included. */
    List<EmployeeView> findEmployees(Collection<Long> employeeIds);

    List<SwapView> findSwaps(SwapStatus status);

    List<SwapView> findSwapsOfEmployee(long employeeId);

    List<SuggestionView> findSuggestions(long shiftId);

    /**
     * {@link #findSuggestions} for many shifts in one query. Ordered by shift and job role, so the
     * suggestions of one shift come in the order the single read gives them.
     */
    List<SuggestionView> findSuggestionsOfShifts(Collection<Long> shiftIds);
}
