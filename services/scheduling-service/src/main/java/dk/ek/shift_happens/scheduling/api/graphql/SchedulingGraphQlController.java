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
import graphql.schema.DataFetchingEnvironment;
import org.dataloader.BatchLoaderEnvironment;
import org.dataloader.DataLoader;
import org.springframework.context.annotation.Profile;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The GraphQL read API of contracts/graphql/scheduling.graphql (P2.11), served at /graphql by the query
 * role only.
 *
 * <p>The controller is deliberately thin: it converts arguments, asks {@link SchedulingQueryService}
 * (which owns the role rules) and returns read-model records. Nested fields are resolved per type, so the
 * schedule page gets shift, assignments, employees and AI suggestions in one request.
 *
 * <p>No nested field reads per parent. Each one queues its key on a DataLoader, and the loader asks the
 * use case once for all parents of that field, so a query costs a fixed number of read-model queries
 * however many shifts and assignments it returns (pinned by {@code GraphQlBatchingTest}). The role rules
 * are untouched by this: they are applied by the root queries, and a nested field is only resolved for a
 * parent a root query has returned to this viewer.
 */
@Controller
@Profile("query")
public class SchedulingGraphQlController {

    private static final String STAFFING = "staffingOfShift";
    private static final String ASSIGNMENTS_OF_SHIFT = "assignmentsOfShift";
    private static final String SUGGESTIONS_OF_SHIFT = "suggestionsOfShift";
    private static final String SHIFT = "shift";
    private static final String ASSIGNMENT = "assignment";
    private static final String EMPLOYEE = "employee";

    private final SchedulingQueryService queries;

    /**
     * Registers one batch loader per kind of nested read. Spring for GraphQL builds a fresh set of
     * DataLoaders from these for every request, so what one request loads is never served to another.
     */
    SchedulingGraphQlController(SchedulingQueryService queries, BatchLoaderRegistry loaders) {
        this.queries = queries;
        loaders.<ShiftView, List<RequiredJobRoleView>>forName(STAFFING).registerMappedBatchLoader(batch(shifts -> {
            Map<Long, List<RequiredJobRoleView>> staffing = queries.requiredJobRoles(shifts);
            return shifts.stream().collect(Collectors.toMap(shift -> shift, shift -> staffing.get(shift.shiftId())));
        }));
        loaders.<Long, List<AssignmentView>>forName(ASSIGNMENTS_OF_SHIFT)
                .registerMappedBatchLoader(batch(queries::assignmentsOfShifts));
        loaders.<Long, List<SuggestionView>>forName(SUGGESTIONS_OF_SHIFT)
                .registerMappedBatchLoader(batch(queries::suggestionsOfShifts));
        loaders.<Long, ShiftView>forName(SHIFT).registerMappedBatchLoader(batch(queries::shiftsById));
        loaders.<Long, AssignmentView>forName(ASSIGNMENT).registerMappedBatchLoader(batch(queries::assignmentsById));
        loaders.<Long, EmployeeView>forName(EMPLOYEE).registerMappedBatchLoader(batch(queries::employeesById));
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
    // ---- nested fields: one batch per field for all parents, never one read per parent ---------------

    @SchemaMapping(typeName = "Shift", field = "requiredJobRoles")
    public CompletableFuture<List<RequiredJobRoleView>> requiredJobRoles(ShiftView shift,
                                                                         DataFetchingEnvironment environment) {
        return load(environment, STAFFING, shift);
    }

    /** Derived from the same batch as requiredJobRoles, so asking for both costs nothing extra. */
    @SchemaMapping(typeName = "Shift", field = "isUnderstaffed")
    public CompletableFuture<Boolean> isUnderstaffed(ShiftView shift, DataFetchingEnvironment environment) {
        return this.<ShiftView, List<RequiredJobRoleView>>load(environment, STAFFING, shift)
                .thenApply(roles -> roles.stream().anyMatch(RequiredJobRoleView::understaffed));
    }

    @SchemaMapping(typeName = "Shift", field = "assignments")
    public CompletableFuture<List<AssignmentView>> assignments(ShiftView shift,
                                                               DataFetchingEnvironment environment) {
        return load(environment, ASSIGNMENTS_OF_SHIFT, shift.shiftId());
    }

    @SchemaMapping(typeName = "Shift", field = "replacementSuggestions")
    public CompletableFuture<List<SuggestionView>> replacementSuggestions(ShiftView shift,
                                                                          DataFetchingEnvironment environment) {
        return load(environment, SUGGESTIONS_OF_SHIFT, shift.shiftId());
    }

    @SchemaMapping(typeName = "Assignment", field = "shift")
    public CompletableFuture<ShiftView> shiftOfAssignment(AssignmentView assignment,
                                                          DataFetchingEnvironment environment) {
        return load(environment, SHIFT, assignment.shiftId());
    }

    @SchemaMapping(typeName = "Assignment", field = "employee")
    public CompletableFuture<EmployeeView> employeeOfAssignment(AssignmentView assignment,
                                                                DataFetchingEnvironment environment) {
        return load(environment, EMPLOYEE, assignment.employeeId());
    }

    @SchemaMapping(typeName = "SwapRequest", field = "assignment")
    public CompletableFuture<AssignmentView> assignmentOfSwap(SwapView swap, DataFetchingEnvironment environment) {
        return load(environment, ASSIGNMENT, swap.originalShiftAssignmentId());
    }

    @SchemaMapping(typeName = "SwapRequest", field = "from")
    public CompletableFuture<EmployeeView> fromOfSwap(SwapView swap, DataFetchingEnvironment environment) {
        return load(environment, EMPLOYEE, swap.employeeFromId());
    }

    @SchemaMapping(typeName = "SwapRequest", field = "to")
    public CompletableFuture<EmployeeView> toOfSwap(SwapView swap, DataFetchingEnvironment environment) {
        return load(environment, EMPLOYEE, swap.employeeToId());
    }

    @SchemaMapping(typeName = "SwapRequest", field = "decidedBy")
    public CompletableFuture<EmployeeView> decidedByOfSwap(SwapView swap, DataFetchingEnvironment environment) {
        return swap.decision() == null ? CompletableFuture.completedFuture(null)
                : load(environment, EMPLOYEE, swap.decision().approverEmployeeId());
    }

    @SchemaMapping(typeName = "RankedCandidate", field = "employee")
    public CompletableFuture<EmployeeView> employeeOfCandidate(RankedCandidate candidate,
                                                               DataFetchingEnvironment environment) {
        return load(environment, EMPLOYEE, candidate.employeeId());
    }

    /**
     * Queues one key on the request's loader. graphql-java dispatches a loader once all fields of a level
     * have queued their keys, which is what turns N parents into one read.
     */
    private <K, V> CompletableFuture<V> load(DataFetchingEnvironment environment, String loader, K key) {
        DataLoader<K, V> dataLoader = environment.getDataLoader(loader);
        return dataLoader.load(key);
    }

    /**
     * A loader answers every key it was given: a key the read model has nothing for gets the default
     * (null for a single object, an empty list for a list field), exactly as the single read would.
     */
    private static <K, V> BiFunction<Set<K>, BatchLoaderEnvironment, Mono<Map<K, V>>> batch(
            Function<Set<K>, Map<K, V>> read) {
        return (keys, environment) -> Mono.fromSupplier(() -> read.apply(keys));
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
