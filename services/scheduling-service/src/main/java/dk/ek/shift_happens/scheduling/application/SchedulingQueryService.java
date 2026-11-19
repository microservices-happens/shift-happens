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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
        return shifts(viewer, from, to, null, null, null).stream()
                .filter(shift -> requiredJobRoles(shift.shiftId()).stream()
                        .anyMatch(RequiredJobRoleView::understaffed))
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
        ShiftView shift = readModel.findShift(shiftId).orElse(null);
        if (shift == null) {
            return List.of();
        }
        List<AssignmentView> staffing = readModel.findAssignmentsOfShift(shiftId).stream()
                .filter(AssignmentView::active)
                .toList();
        Map<Long, EmployeeView> employees = readModel.findEmployees(staffing.stream()
                        .map(AssignmentView::employeeId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(EmployeeView::employeeId, employee -> employee));
        return shift.requiredJobRoles().stream()
                .map(role -> new RequiredJobRoleView(role.jobRoleId(), role.requiredEmployeeCount(),
                        (int) staffing.stream().filter(assignment -> {
                            EmployeeView employee = employees.get(assignment.employeeId());
                            return employee != null && !employee.deleted()
                                    && employee.hasJobRole(role.jobRoleId());
                        }).count()))
                .toList();
    }

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
