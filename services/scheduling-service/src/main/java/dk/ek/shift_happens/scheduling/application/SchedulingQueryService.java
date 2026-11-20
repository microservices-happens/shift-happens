package dk.ek.shift_happens.scheduling.application;

import dk.ek.shift_happens.scheduling.domain.AssignmentView;
import dk.ek.shift_happens.scheduling.domain.EmployeeView;
import dk.ek.shift_happens.scheduling.domain.ReadModelQueries;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleView;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import dk.ek.shift_happens.scheduling.domain.ShiftView;
import dk.ek.shift_happens.scheduling.domain.SuggestionView;
import dk.ek.shift_happens.scheduling.domain.SwapStatus;
import dk.ek.shift_happens.scheduling.domain.SwapView;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The read use cases behind contracts/graphql/scheduling.graphql (P2.11).
 *
 * <p>All role rules live here rather than in the resolvers, so they are testable and there is one place
 * to read: an Employee only sees the shifts they are assigned to, only their own schedule and only the
 * swaps they are part of. A Manager or Administrator sees everything.
 *
 * <p>{@code assignedCount} and {@code isUnderstaffed} are derived, not stored: they count active
 * assignments whose employee holds the required job role, which is why the read model keeps the employee
 * replica next to the assignments.
 *
 * <p>The nested GraphQL fields are answered by the batch reads at the bottom, one call for all parents of
 * a field, so a query costs a fixed number of read-model queries however many shifts it returns.
 */
@Service
@Profile("query")
public class SchedulingQueryService {

    private final ReadModelQueries readModel;

    SchedulingQueryService(ReadModelQueries readModel) {
        this.readModel = readModel;
    }

    public List<ShiftView> shifts(Viewer viewer, Instant from, Instant to, Long departmentId,
                                  Long workLocationId, ShiftStatus status) {
        if (viewer.seesEverything()) {
            return readModel.findShifts(from, to, departmentId, workLocationId, status);
        }
        return readModel.findShiftsForEmployee(viewer.employeeId(), from, to).stream()
                .filter(shift -> departmentId == null || shift.departmentId() == departmentId)
                .filter(shift -> workLocationId == null || shift.workLocationId() == workLocationId)
                .filter(shift -> status == null || shift.status() == status)
                .toList();
    }

    /** Null when the shift does not exist or is not visible: an Employee only sees their own shifts. */
    public ShiftView shift(Viewer viewer, long shiftId) {
        ShiftView shift = shiftOf(shiftId);
        if (shift == null || viewer.seesEverything()) {
            return shift;
        }
        boolean own = readModel.findAssignmentsOfShift(shiftId).stream()
                .anyMatch(assignment -> assignment.active() && assignment.employeeId() == viewer.employeeId());
        return own ? shift : null;
    }

    public List<AssignmentView> employeeSchedule(Viewer viewer, long employeeId, Instant from, Instant to) {
        if (!viewer.seesEverything() && !Objects.equals(viewer.employeeId(), employeeId)) {
            throw new ViewerNotAllowedException("an Employee may only read their own schedule");
        }
        Set<Long> shiftsInWindow = readModel.findShifts(from, to, null, null, null).stream()
                .map(ShiftView::shiftId).collect(Collectors.toSet());
        return readModel.findAssignmentsOfEmployee(employeeId, from, to).stream()
                .filter(assignment -> shiftsInWindow.contains(assignment.shiftId()))
                .toList();
    }

    public List<ShiftView> understaffedShifts(Viewer viewer, Instant from, Instant to) {
        List<ShiftView> shifts = shifts(viewer, from, to, null, null, null);
        Map<Long, List<RequiredJobRoleView>> staffing = requiredJobRoles(shifts);
        return shifts.stream()
                .filter(shift -> staffing.get(shift.shiftId()).stream().anyMatch(RequiredJobRoleView::understaffed))
                .toList();
    }

    public List<SwapView> swaps(Viewer viewer, SwapStatus status) {
        List<SwapView> found = viewer.seesEverything()
                ? readModel.findSwaps(status)
                : readModel.findSwapsOfEmployee(viewer.employeeId());
        return found.stream().filter(swap -> status == null || swap.status() == status).toList();
    }

    /** Staffing per required job role of one shift (RequiredJobRole.assignedCount). */
    public List<RequiredJobRoleView> requiredJobRoles(long shiftId) {
        return readModel.findShift(shiftId)
                .map(shift -> requiredJobRoles(List.of(shift)).get(shiftId))
                .orElse(List.of());
    }

    // ---- batch reads for the nested GraphQL fields ---------------------------------------------------
    //
    // Each method answers for any number of parents in a fixed number of read-model queries, and
    // gives every parent exactly what the single read above or below gives it. None of them takes a
    // Viewer, because the nested fields never did: what a viewer may see is decided by the root queries
    // above, and a nested field is only resolved for a parent one of them has returned.

    /**
     * Staffing per required job role for many shifts, by shift id, in two queries (the assignments
     * of all shifts, then their employees). The required roles are those of the given snapshots.
     */
    public Map<Long, List<RequiredJobRoleView>> requiredJobRoles(Collection<ShiftView> shifts) {
        if (shifts.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<AssignmentView>> staffing = readModel.findAssignmentsOfShifts(idsOf(shifts)).stream()
                .filter(AssignmentView::active)
                .collect(Collectors.groupingBy(AssignmentView::shiftId));
        Map<Long, EmployeeView> employees = readModel.findEmployees(staffing.values().stream()
                        .flatMap(List::stream).map(AssignmentView::employeeId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(EmployeeView::employeeId, employee -> employee));
        Map<Long, List<RequiredJobRoleView>> roles = new HashMap<>();
        for (ShiftView shift : shifts) {
            List<AssignmentView> assigned = staffing.getOrDefault(shift.shiftId(), List.of());
            roles.put(shift.shiftId(), shift.requiredJobRoles().stream()
                    .map(role -> new RequiredJobRoleView(role.jobRoleId(), role.requiredEmployeeCount(),
                            (int) assigned.stream().filter(assignment -> {
                                EmployeeView employee = employees.get(assignment.employeeId());
                                return employee != null && !employee.deleted()
                                        && employee.hasJobRole(role.jobRoleId());
                            }).count()))
                    .toList());
        }
        return roles;
    }

    /** {@link #assignmentsOfShift} for many shifts in one query; a shift without assignments gets an empty list. */
    public Map<Long, List<AssignmentView>> assignmentsOfShifts(Collection<Long> shiftIds) {
        return perShift(shiftIds, readModel.findAssignmentsOfShifts(shiftIds), AssignmentView::shiftId);
    }

    /** {@link #suggestionsOfShift} for many shifts in one query; a shift without suggestions gets an empty list. */
    public Map<Long, List<SuggestionView>> suggestionsOfShifts(Collection<Long> shiftIds) {
        return perShift(shiftIds, readModel.findSuggestionsOfShifts(shiftIds), SuggestionView::shiftId);
    }

    /** {@link #shiftOf} for many ids in one query; a missing or deleted shift has no entry. */
    public Map<Long, ShiftView> shiftsById(Collection<Long> shiftIds) {
        return readModel.findShiftsByIds(shiftIds).stream()
                .filter(shift -> !shift.deleted())
                .collect(Collectors.toMap(ShiftView::shiftId, shift -> shift));
    }

    /** {@link #assignment} for many ids in one query; a missing or deleted assignment has no entry. */
    public Map<Long, AssignmentView> assignmentsById(Collection<Long> shiftAssignmentIds) {
        return readModel.findAssignmentsByIds(shiftAssignmentIds).stream()
                .filter(assignment -> !assignment.deleted())
                .collect(Collectors.toMap(AssignmentView::shiftAssignmentId, assignment -> assignment));
    }

    /** {@link #employee} for many ids in one query; a missing or deleted employee has no entry. */
    public Map<Long, EmployeeView> employeesById(Collection<Long> employeeIds) {
        return readModel.findEmployees(employeeIds).stream()
                .filter(employee -> !employee.deleted())
                .collect(Collectors.toMap(EmployeeView::employeeId, employee -> employee));
    }

    /** Groups rows that arrive ordered, keeping that order inside each shift. */
    private static <T> Map<Long, List<T>> perShift(Collection<Long> shiftIds, List<T> rows,
                                                   Function<T, Long> shiftIdOf) {
        Map<Long, List<T>> grouped = new HashMap<>();
        shiftIds.forEach(shiftId -> grouped.put(shiftId, new ArrayList<>()));
        rows.forEach(row -> grouped.get(shiftIdOf.apply(row)).add(row));
        return grouped;
    }

    private static Set<Long> idsOf(Collection<ShiftView> shifts) {
        return shifts.stream().map(ShiftView::shiftId).collect(Collectors.toSet());
    }

    // ---- single reads --------------------------------------------------------------------------------

    public List<AssignmentView> assignmentsOfShift(long shiftId) {
        return readModel.findAssignmentsOfShift(shiftId);
    }

    public List<SuggestionView> suggestionsOfShift(long shiftId) {
        return readModel.findSuggestions(shiftId);
    }

    public ShiftView shiftOf(long shiftId) {
        return readModel.findShift(shiftId).filter(shift -> !shift.deleted()).orElse(null);
    }

    public AssignmentView assignment(long shiftAssignmentId) {
        return readModel.findAssignment(shiftAssignmentId)
                .filter(assignment -> !assignment.deleted()).orElse(null);
    }

    public EmployeeView employee(long employeeId) {
        return readModel.findEmployee(employeeId).filter(employee -> !employee.deleted()).orElse(null);
    }
}
