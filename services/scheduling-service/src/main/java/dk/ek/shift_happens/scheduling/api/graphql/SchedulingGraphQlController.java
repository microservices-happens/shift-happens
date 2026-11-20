package dk.ek.shift_happens.scheduling.api.graphql;

import dk.ek.shift_happens.scheduling.application.SchedulingQueryService;
import dk.ek.shift_happens.scheduling.domain.AssignmentView;
import dk.ek.shift_happens.scheduling.domain.EmployeeView;
import dk.ek.shift_happens.scheduling.domain.RankedCandidate;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleView;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import dk.ek.shift_happens.scheduling.domain.ShiftView;
import dk.ek.shift_happens.scheduling.domain.SuggestionView;
import dk.ek.shift_happens.scheduling.domain.SwapStatus;
import dk.ek.shift_happens.scheduling.domain.SwapView;
import org.springframework.context.annotation.Profile;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * The GraphQL read API of contracts/graphql/scheduling.graphql (P2.11), served at /graphql by the query
 * role only.
 *
 * <p>The controller is deliberately thin: it converts arguments, asks {@link SchedulingQueryService}
 * (which owns the role rules) and returns read-model records. Nested fields are resolved per type, so the
 * schedule page gets shift, assignments, employees and AI suggestions in one request.
 */
@Controller
@Profile("query")
public class SchedulingGraphQlController {

    private final SchedulingQueryService queries;

    SchedulingGraphQlController(SchedulingQueryService queries) {
        this.queries = queries;
    }

    @QueryMapping
    public List<ShiftView> shifts(@Argument String from, @Argument String to, @Argument Integer departmentId,
                                  @Argument Integer workLocationId, @Argument String status,
                                  Authentication authentication) {
        return queries.shifts(Viewers.of(authentication), GraphQlTime.instant(from, "from"),
                GraphQlTime.instant(to, "to"), id(departmentId), id(workLocationId), shiftStatus(status));
    }

    @QueryMapping
    public ShiftView shift(@Argument Integer shiftId, Authentication authentication) {
        return queries.shift(Viewers.of(authentication), id(shiftId));
    }

    @QueryMapping
    public List<AssignmentView> employeeSchedule(@Argument Integer employeeId, @Argument String from,
                                                 @Argument String to, Authentication authentication) {
        return queries.employeeSchedule(Viewers.of(authentication), id(employeeId),
                GraphQlTime.instant(from, "from"), GraphQlTime.instant(to, "to"));
    }

    @QueryMapping
    public List<ShiftView> understaffedShifts(@Argument String from, @Argument String to,
                                              Authentication authentication) {
        return queries.understaffedShifts(Viewers.of(authentication), GraphQlTime.instant(from, "from"),
                GraphQlTime.instant(to, "to"));
    }

    @QueryMapping
    public List<SwapView> swapRequests(@Argument String status, Authentication authentication) {
        return queries.swaps(Viewers.of(authentication), swapStatus(status));
    }
    @SchemaMapping(typeName = "Shift", field = "requiredJobRoles")
    public List<RequiredJobRoleView> requiredJobRoles(ShiftView shift) {
        return queries.requiredJobRoles(shift.shiftId());
    }

    @SchemaMapping(typeName = "Shift", field = "isUnderstaffed")
    public boolean isUnderstaffed(ShiftView shift) {
        return queries.requiredJobRoles(shift.shiftId()).stream()
                .anyMatch(RequiredJobRoleView::understaffed);
    }

    @SchemaMapping(typeName = "Shift", field = "assignments")
    public List<AssignmentView> assignments(ShiftView shift) {
        return queries.assignmentsOfShift(shift.shiftId());
    }

    @SchemaMapping(typeName = "Shift", field = "replacementSuggestions")
    public List<SuggestionView> replacementSuggestions(ShiftView shift) {
        return queries.suggestionsOfShift(shift.shiftId());
    }

    @SchemaMapping(typeName = "Assignment", field = "shift")
    public ShiftView shiftOfAssignment(AssignmentView assignment) {
        return queries.shiftOf(assignment.shiftId());
    }

    @SchemaMapping(typeName = "Assignment", field = "employee")
    public EmployeeView employeeOfAssignment(AssignmentView assignment) {
        return queries.employee(assignment.employeeId());
    }

    @SchemaMapping(typeName = "SwapRequest", field = "assignment")
    public AssignmentView assignmentOfSwap(SwapView swap) {
        return queries.assignment(swap.originalShiftAssignmentId());
    }

    @SchemaMapping(typeName = "SwapRequest", field = "from")
    public EmployeeView fromOfSwap(SwapView swap) {
        return queries.employee(swap.employeeFromId());
    }

    @SchemaMapping(typeName = "SwapRequest", field = "to")
    public EmployeeView toOfSwap(SwapView swap) {
        return queries.employee(swap.employeeToId());
    }

    @SchemaMapping(typeName = "SwapRequest", field = "decidedBy")
    public EmployeeView decidedByOfSwap(SwapView swap) {
        return swap.decision() == null ? null : queries.employee(swap.decision().approverEmployeeId());
    }

    @SchemaMapping(typeName = "RankedCandidate", field = "employee")
    public EmployeeView employeeOfCandidate(RankedCandidate candidate) {
        return queries.employee(candidate.employeeId());
    }

    /** An optional filter stays null: {@code 0} would be a filter that matches nothing. */
    private static Long id(Integer value) {
        return value == null ? null : value.longValue();
    }

    private static ShiftStatus shiftStatus(String status) {
        return status == null || status.isBlank() ? null : ShiftStatus.valueOf(status.trim());
    }

    private static SwapStatus swapStatus(String status) {
        return status == null || status.isBlank() ? null : SwapStatus.valueOf(status.trim());
    }
}
