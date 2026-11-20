package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.application.SchedulingQueryService;
import dk.ek.shift_happens.scheduling.application.Viewer;
import dk.ek.shift_happens.scheduling.config.JwtConfig;
import dk.ek.shift_happens.scheduling.config.QuerySecurityConfig;
import dk.ek.shift_happens.scheduling.domain.ApprovalDecision;
import dk.ek.shift_happens.scheduling.domain.AssignmentStatus;
import dk.ek.shift_happens.scheduling.domain.AssignmentView;
import dk.ek.shift_happens.scheduling.domain.EmployeeView;
import dk.ek.shift_happens.scheduling.domain.RankedCandidate;
import dk.ek.shift_happens.scheduling.domain.ReadModelQueries;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleSnapshot;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleView;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import dk.ek.shift_happens.scheduling.domain.ShiftView;
import dk.ek.shift_happens.scheduling.domain.SuggestionSource;
import dk.ek.shift_happens.scheduling.domain.SuggestionView;
import dk.ek.shift_happens.scheduling.domain.SwapDecision;
import dk.ek.shift_happens.scheduling.domain.SwapStatus;
import dk.ek.shift_happens.scheduling.domain.SwapView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.graphql.GraphQlAutoConfiguration;
import org.springframework.boot.autoconfigure.graphql.servlet.GraphQlWebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Pins the batching of the nested GraphQL fields (P2.11), without containers: the real endpoint, filter
 * chain, JWT decoder, controller and {@link SchedulingQueryService}, on an in-memory read model that
 * counts every call made on the {@link ReadModelQueries} port. One call on the port is one MongoDB round
 * trip in {@code MongoReadModel}.
 *
 * <p>Three things must hold:
 * <ul>
 *   <li>the number of read-model calls of a query does not grow with the number of shifts, assignments,
 *       swaps or candidates it returns (the N+1 the resolvers had before);</li>
 *   <li>the answer is exactly what reading one parent at a time gives, in the same order;</li>
 *   <li>an Employee still gets only their own shifts and swaps.</li>
 * </ul>
 * {@code GraphQlRoundTripsIT} repeats the first point against a real MongoDB.
 */
@WebMvcTest
@ActiveProfiles("query")
@ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class})
@Import({QuerySecurityConfig.class, JwtConfig.class, SchedulingQueryService.class,
        GraphQlBatchingTest.GraphQlLayer.class})
class GraphQlBatchingTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant MONDAY = Instant.parse("2027-04-05T00:00:00Z");
    private static final String FROM = MONDAY.toString();
    private static final String TO = MONDAY.plus(Duration.ofDays(7)).toString();
    private static final int ASSIGNMENTS_PER_SHIFT = 4;
    private static final long EMPLOYEE = 3;

    /** The query the CQRS benchmark measures (docs/measurements/cqrs-read-model.md). */
    private static final String WEEK = """
            { shifts(from: "%s", to: "%s") {
                shiftId shiftName status isUnderstaffed
                requiredJobRoles { jobRoleId requiredEmployeeCount assignedCount }
                assignments { shiftAssignmentId status employee { employeeId firstName lastName } }
              } }
            """.formatted(FROM, TO);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CountingReadModel readModel;
    @Autowired
    private SchedulingQueryService queries;

    @BeforeEach
    void emptyTheReadModel() {
        readModel.clear();
    }

    @Test
    void theWeekOfAManagerCostsTheSameReadsForTwoShiftsAndForSixty() throws Exception {
        List<String> few = readsOf(WEEK, TestJwks.manager(), 2);
        List<String> many = readsOf(WEEK, TestJwks.manager(), 60);

        // The shifts; the staffing behind requiredJobRoles and isUnderstaffed (assignments, employees);
        // the assignments field; the employees of all assignments.
        assertThat(few).containsExactlyInAnyOrder("findShifts", "findAssignmentsOfShifts", "findEmployees",
                "findAssignmentsOfShifts", "findEmployees");
        assertThat(many).as("60 shifts with %d assignments each", ASSIGNMENTS_PER_SHIFT)
                .containsExactlyInAnyOrderElementsOf(few);
    }

    @Test
    void theWeekOfAnEmployeeCostsTheSameReadsHoweverManyShiftsTheyWork() throws Exception {
        List<String> few = readsOf(WEEK, TestJwks.employee(EMPLOYEE), 4);
        List<String> many = readsOf(WEEK, TestJwks.employee(EMPLOYEE), 60);

        assertThat(few).containsExactlyInAnyOrder("findShiftsForEmployee", "findAssignmentsOfShifts",
                "findEmployees", "findAssignmentsOfShifts", "findEmployees");
        assertThat(many).containsExactlyInAnyOrderElementsOf(few);
    }

    @Test
    void theBatchedAnswerIsWhatReadingOneParentAtATimeGives() throws Exception {
        seedShifts(12);
        // A shift nobody is assigned to, and one whose only assignment is soft-deleted.
        readModel.shifts.add(shift(900, null));
        readModel.shifts.add(shift(901, null));
        readModel.assignments.add(new AssignmentView(90_100, 901, 1, AssignmentStatus.ASSIGNED, MONDAY, null, null,
                MONDAY, MONDAY.plus(Duration.ofHours(8)), 2, MONDAY));

        JsonNode answer = data(WEEK, TestJwks.manager()).path("shifts");

        Viewer manager = new Viewer(900L, Viewer.MANAGER);
        List<Map<String, Object>> expected = new ArrayList<>();
        for (ShiftView shift : queries.shifts(manager, MONDAY, MONDAY.plus(Duration.ofDays(7)), null, null, null)) {
            // The resolvers before batching: one use-case call per shift and per assignment.
            List<RequiredJobRoleView> roles = queries.requiredJobRoles(shift.shiftId());
            Map<String, Object> expectedShift = new LinkedHashMap<>();
            expectedShift.put("shiftId", shift.shiftId());
            expectedShift.put("shiftName", shift.shiftName());
            expectedShift.put("status", shift.status().name());
            expectedShift.put("isUnderstaffed", roles.stream().anyMatch(RequiredJobRoleView::understaffed));
            expectedShift.put("requiredJobRoles", roles.stream().map(role -> Map.of(
                    "jobRoleId", role.jobRoleId(),
                    "requiredEmployeeCount", role.requiredEmployeeCount(),
                    "assignedCount", role.assignedCount())).toList());
            expectedShift.put("assignments", queries.assignmentsOfShift(shift.shiftId()).stream().map(assignment -> {
                EmployeeView employee = queries.employee(assignment.employeeId());
                return Map.of(
                        "shiftAssignmentId", assignment.shiftAssignmentId(),
                        "status", assignment.status().name(),
                        "employee", Map.of("employeeId", employee.employeeId(),
                                "firstName", employee.firstName(), "lastName", employee.lastName()));
            }).toList());
            expected.add(expectedShift);
        }
        // 12 seeded, of which shift 9 is deleted, plus the two without staff.
        assertThat(expected).hasSize(13);
        assertThat(answer).isEqualTo(JSON.readTree(JSON.writeValueAsString(expected)));
        // The data is not trivial: both outcomes of the staffing rule, and the two shifts without staff.
        assertThat(answer.findValuesAsText("isUnderstaffed")).contains("true", "false");
        assertThat(byShiftId(answer, 900).path("assignments")).isEmpty();
        assertThat(byShiftId(answer, 901).path("assignments")).isEmpty();
    }

    @Test
    void anEmployeeStillGetsOnlyTheirOwnShifts() throws Exception {
        seedShifts(20);

        JsonNode mine = data(WEEK, TestJwks.employee(EMPLOYEE)).path("shifts");
        JsonNode all = data(WEEK, TestJwks.manager()).path("shifts");

        List<Long> expected = readModel.assignments.stream()
                .filter(assignment -> assignment.employeeId() == EMPLOYEE && !assignment.deleted())
                .map(AssignmentView::shiftId).filter(shiftId -> shiftId % 9 != 0).distinct().sorted().toList();
        assertThat(expected).isNotEmpty().hasSizeLessThan(18);
        assertThat(mine.findValues("shiftId").stream().map(JsonNode::asLong).sorted().toList())
                .isEqualTo(expected);
        assertThat(all).as("shifts 9 and 18 are deleted").hasSize(18);
        // Each of those shifts is answered exactly as the manager sees that shift: the batch of one
        // viewer holds only the parents that viewer was given.
        for (JsonNode shift : mine) {
            assertThat(shift).isEqualTo(byShiftId(all, shift.path("shiftId").asLong()));
        }
    }

    @Test
    void swapsWithAssignmentShiftAndEmployeesCostTheSameReadsForTwoAndForForty() throws Exception {
        String query = """
                { swapRequests { shiftSwapId status
                    assignment { shiftAssignmentId shift { shiftId shiftName } employee { employeeId } }
                    from { employeeId firstName } to { employeeId firstName }
                    decidedBy { employeeId firstName } } }
                """;
        seedShifts(60);
        seedSwaps(2);
        readModel.calls.clear();
        JsonNode two = data(query, TestJwks.manager()).path("swapRequests");
        List<String> few = List.copyOf(readModel.calls);

        seedSwaps(40);
        readModel.calls.clear();
        JsonNode forty = data(query, TestJwks.manager()).path("swapRequests");
        List<String> many = List.copyOf(readModel.calls);

        // The swaps; their assignments together with from, to and decidedBy in one employee read; then
        // the shifts of the assignments. The employees of the assignments cost nothing here, because the
        // request already loaded them as from or to; one not yet loaded would add a single read for all.
        assertThat(few).containsExactlyInAnyOrder("findSwaps", "findAssignmentsByIds", "findEmployees",
                "findShiftsByIds");
        assertThat(many).containsExactlyInAnyOrderElementsOf(few);
        assertThat(two).hasSize(2);
        assertThat(forty).hasSize(40);
        for (JsonNode swap : forty) {
            SwapView stored = readModel.swaps.stream()
                    .filter(candidate -> candidate.shiftSwapId() == swap.path("shiftSwapId").asLong())
                    .findFirst().orElseThrow();
            assertThat(swap.path("assignment").path("shiftAssignmentId").asLong())
                    .isEqualTo(stored.originalShiftAssignmentId());
            assertThat(swap.path("assignment").path("shift").path("shiftId").asLong()).isEqualTo(stored.shiftId());
            assertThat(swap.path("from").path("employeeId").asLong()).isEqualTo(stored.employeeFromId());
            assertThat(swap.path("to").path("employeeId").asLong()).isEqualTo(stored.employeeToId());
            if (stored.decision() == null) {
                assertThat(swap.path("decidedBy").isNull()).as("an undecided swap has no decidedBy").isTrue();
            } else {
                assertThat(swap.path("decidedBy").path("employeeId").asLong())
                        .isEqualTo(stored.decision().approverEmployeeId());
            }
        }
    }

    @Test
    void anEmployeeStillGetsOnlyTheSwapsTheyArePartOf() throws Exception {
        seedShifts(60);
        seedSwaps(40);

        JsonNode swaps = data("{ swapRequests { shiftSwapId from { employeeId } to { employeeId } } }",
                TestJwks.employee(EMPLOYEE)).path("swapRequests");

        assertThat(swaps).isNotEmpty().hasSizeLessThan(40);
        assertThat(swaps).allSatisfy(swap -> assertThat(List.of(swap.path("from").path("employeeId").asLong(),
                swap.path("to").path("employeeId").asLong())).contains(EMPLOYEE));
    }

    @Test
    void aDeletedDeciderIsNullAsBefore() throws Exception {
        seedShifts(2);
        readModel.employees.add(employee(77, MONDAY));
        readModel.swaps.add(new SwapView(1, assignmentId(1, 0), 1, 1, 2, SwapStatus.APPROVED, "swap", MONDAY,
                new SwapDecision(77, ApprovalDecision.APPROVED, "ok", MONDAY), 2));

        JsonNode swap = data("{ swapRequests { shiftSwapId decidedBy { employeeId } } }", TestJwks.manager())
                .path("swapRequests").get(0);

        assertThat(queries.employee(77)).isNull();
        assertThat(swap.path("decidedBy").isNull()).isTrue();
    }

    @Test
    void suggestionsWithCandidatesAndTheScheduleOfAnEmployeeAreBatchedToo() throws Exception {
        String suggestions = """
                { shifts(from: "%s", to: "%s") { shiftId
                    replacementSuggestions { jobRoleId reason candidates { rank employee { employeeId firstName } } }
                  } }
                """.formatted(FROM, TO);
        List<String> few = readsOf(suggestions, TestJwks.manager(), 2);
        List<String> many = readsOf(suggestions, TestJwks.manager(), 60);
        assertThat(few).containsExactlyInAnyOrder("findShifts", "findSuggestionsOfShifts", "findEmployees");
        assertThat(many).containsExactlyInAnyOrderElementsOf(few);

        JsonNode shifts = data(suggestions, TestJwks.manager()).path("shifts");
        for (JsonNode shift : shifts) {
            List<SuggestionView> expected = queries.suggestionsOfShift(shift.path("shiftId").asLong());
            assertThat(shift.path("replacementSuggestions").findValues("jobRoleId").stream().map(JsonNode::asLong))
                    .containsExactlyElementsOf(expected.stream().map(SuggestionView::jobRoleId).toList());
            assertThat(shift.path("replacementSuggestions").findValues("candidates").stream()
                    .flatMap(candidates -> candidates.findValues("employeeId").stream()).map(JsonNode::asLong))
                    .containsExactlyElementsOf(expected.stream().flatMap(suggestion -> suggestion.candidates().stream())
                            .map(RankedCandidate::employeeId).toList());
        }

        String schedule = """
                { employeeSchedule(employeeId: %d, from: "%s", to: "%s") {
                    shiftAssignmentId shift { shiftId shiftName } employee { employeeId } } }
                """.formatted(EMPLOYEE, FROM, TO);
        few = readsOf(schedule, TestJwks.employee(EMPLOYEE), 4);
        many = readsOf(schedule, TestJwks.employee(EMPLOYEE), 60);
        assertThat(few).containsExactlyInAnyOrder("findShifts", "findAssignmentsOfEmployee", "findShiftsByIds",
                "findEmployees");
        assertThat(many).containsExactlyInAnyOrderElementsOf(few);
    }

    // ---- harness -----------------------------------------------------------------------------------

    /** Seeds {@code shifts} shifts, answers the query and returns the read-model calls it took. */
    private List<String> readsOf(String query, String token, int shifts) throws Exception {
        readModel.clear();
        seedShifts(shifts);
        readModel.calls.clear();
        JsonNode data = data(query, token);
        assertThat(data.elements().next()).as("%d shifts seeded", shifts).isNotEmpty();
        return List.copyOf(readModel.calls);
    }

    private JsonNode data(String query, String token) throws Exception {
        MvcResult result = mvc.perform(post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .content(JSON.writeValueAsString(Map.of("query", query)))).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("errors").isMissingNode() || body.path("errors").isEmpty())
                .as("answered without errors: %s", body).isTrue();
        return body.path("data");
    }

    private static JsonNode byShiftId(JsonNode shifts, long shiftId) {
        for (JsonNode shift : shifts) {
            if (shift.path("shiftId").asLong() == shiftId) {
                return shift;
            }
        }
        throw new AssertionError("no shift " + shiftId);
    }

    /**
     * Ten employees, and per shift two required roles, four assignments rotating over the employees (some
     * cancelled, some soft-deleted) and two suggestions with two candidates. Every ninth shift is deleted.
     */
    private void seedShifts(int shifts) {
        if (readModel.employees.isEmpty()) {
            for (long id = 1; id <= 10; id++) {
                readModel.employees.add(employee(id, null));
            }
        }
        for (long shiftId = 1; shiftId <= shifts; shiftId++) {
            readModel.shifts.add(shift(shiftId, shiftId % 9 == 0 ? MONDAY : null));
            for (int seat = 0; seat < ASSIGNMENTS_PER_SHIFT; seat++) {
                long assignmentId = assignmentId(shiftId, seat);
                // Employee 10 is never assigned, so no assignment points at an employee that is missing.
                long employeeId = (shiftId * 2 + seat * 2L) % 9 + 1;
                AssignmentStatus status = assignmentId % 7 == 0 ? AssignmentStatus.CANCELLED
                        : AssignmentStatus.ASSIGNED;
                readModel.assignments.add(new AssignmentView(assignmentId, shiftId, employeeId, status, MONDAY,
                        null, null, startOf(shiftId), startOf(shiftId).plus(Duration.ofHours(8)), 1,
                        assignmentId % 11 == 0 ? MONDAY : null));
            }
            for (long jobRoleId : List.of(2L, 1L)) {
                readModel.suggestions.add(new SuggestionView(shiftId, jobRoleId, SuggestionSource.RULE, null,
                        MONDAY, "ranked by free hours", List.of(new RankedCandidate(shiftId % 9 + 1, 1),
                        new RankedCandidate((shiftId + 4) % 9 + 1, 2))));
            }
        }
    }

    private void seedSwaps(int swaps) {
        readModel.swaps.clear();
        for (long swapId = 1; swapId <= swaps; swapId++) {
            // Skips every ninth shift: those are deleted, and Assignment.shift is non-null in the schema.
            long shiftId = swapId + (swapId - 1) / 8;
            long from = swapId % 9 + 1;
            long to = (swapId + 3) % 9 + 1;
            SwapDecision decision = swapId % 2 == 0 ? null
                    : new SwapDecision(swapId % 3 + 1, ApprovalDecision.APPROVED, "ok", MONDAY);
            readModel.swaps.add(new SwapView(swapId, assignmentId(shiftId, 0), shiftId, from, to,
                    decision == null ? SwapStatus.PENDING : SwapStatus.APPROVED, "swap", MONDAY.plusSeconds(swapId),
                    decision, 1));
        }
    }

    private static long assignmentId(long shiftId, int seat) {
        // Never a multiple of 11, so the first assignment of a shift is one a swap can point at.
        return seat == 0 ? shiftId * 110 + 1 : shiftId * 110 + 1 + seat;
    }

    private static Instant startOf(long shiftId) {
        return MONDAY.plus(Duration.ofHours(6 + shiftId % 100));
    }

    private static ShiftView shift(long shiftId, Instant deletedAt) {
        return new ShiftView(shiftId, 2, 3, "Shift " + shiftId, startOf(shiftId),
                startOf(shiftId).plus(Duration.ofHours(8)), ShiftStatus.PUBLISHED,
                List.of(new RequiredJobRoleSnapshot(1, 1), new RequiredJobRoleSnapshot(2, 2)), 1, deletedAt);
    }

    private static EmployeeView employee(long employeeId, Instant deletedAt) {
        return new EmployeeView(employeeId, "First" + employeeId, "Last" + employeeId,
                employeeId % 2 == 0 ? List.of(1L, 2L) : List.of(2L), "ACTIVE", 37, 1, deletedAt);
    }

    /** The GraphQL controller, scalars and error resolver, which a web slice does not scan by itself. */
    @TestConfiguration
    @ComponentScan("dk.ek.shift_happens.scheduling.api.graphql")
    static class GraphQlLayer {

        @Bean
        CountingReadModel countingReadModel() {
            return new CountingReadModel();
        }
    }

    /**
     * The read port over plain lists, with the filters and orders of {@code MongoReadModel}. Every call
     * is recorded by name: one call here is one round trip there.
     */
    static class CountingReadModel implements ReadModelQueries {

        final List<String> calls = new ArrayList<>();
        final List<ShiftView> shifts = new ArrayList<>();
        final List<AssignmentView> assignments = new ArrayList<>();
        final List<EmployeeView> employees = new ArrayList<>();
        final List<SwapView> swaps = new ArrayList<>();
        final List<SuggestionView> suggestions = new ArrayList<>();

        void clear() {
            calls.clear();
            shifts.clear();
            assignments.clear();
            employees.clear();
            swaps.clear();
            suggestions.clear();
        }

        @Override
        public Optional<ShiftView> findShift(long shiftId) {
            calls.add("findShift");
            return shifts.stream().filter(shift -> shift.shiftId() == shiftId).findFirst();
        }

        @Override
        public List<ShiftView> findShiftsByIds(Collection<Long> shiftIds) {
            calls.add("findShiftsByIds");
            return shifts.stream().filter(shift -> shiftIds.contains(shift.shiftId())).toList();
        }

        @Override
        public List<ShiftView> findShifts(Instant from, Instant to, Long departmentId, Long workLocationId,
                                          ShiftStatus status) {
            calls.add("findShifts");
            return shifts.stream()
                    .filter(shift -> !shift.deleted() && shift.overlaps(from, to))
                    .filter(shift -> departmentId == null || shift.departmentId() == departmentId)
                    .filter(shift -> workLocationId == null || shift.workLocationId() == workLocationId)
                    .filter(shift -> status == null || shift.status() == status)
                    .sorted(Comparator.comparing(ShiftView::startDatetime).thenComparing(ShiftView::shiftId))
                    .toList();
        }

        @Override
        public List<ShiftView> findShiftsForEmployee(long employeeId, Instant from, Instant to) {
            calls.add("findShiftsForEmployee");
            List<Long> own = assignments.stream()
                    .filter(assignment -> assignment.employeeId() == employeeId && !assignment.deleted())
                    .map(AssignmentView::shiftId).toList();
            return shifts.stream()
                    .filter(shift -> own.contains(shift.shiftId()) && !shift.deleted() && shift.overlaps(from, to))
                    .sorted(Comparator.comparing(ShiftView::startDatetime).thenComparing(ShiftView::shiftId))
                    .toList();
        }

        @Override
        public List<AssignmentView> findAssignmentsOfShift(long shiftId) {
            calls.add("findAssignmentsOfShift");
            return assignmentsOf(List.of(shiftId));
        }

        @Override
        public List<AssignmentView> findAssignmentsOfShifts(Collection<Long> shiftIds) {
            calls.add("findAssignmentsOfShifts");
            return assignmentsOf(shiftIds);
        }

        private List<AssignmentView> assignmentsOf(Collection<Long> shiftIds) {
            return assignments.stream()
                    .filter(assignment -> shiftIds.contains(assignment.shiftId()) && !assignment.deleted())
                    .sorted(Comparator.comparing(AssignmentView::shiftAssignmentId))
                    .toList();
        }

        @Override
        public List<AssignmentView> findAssignmentsOfEmployee(long employeeId, Instant from, Instant to) {
            calls.add("findAssignmentsOfEmployee");
            return assignments.stream()
                    .filter(assignment -> assignment.employeeId() == employeeId && !assignment.deleted())
                    .filter(assignment -> assignment.shiftStartDatetime().isBefore(to)
                            && assignment.shiftEndDatetime().isAfter(from))
                    .sorted(Comparator.comparing(AssignmentView::shiftStartDatetime)
                            .thenComparing(AssignmentView::shiftAssignmentId))
                    .toList();
        }

        @Override
        public Optional<AssignmentView> findAssignment(long shiftAssignmentId) {
            calls.add("findAssignment");
            return assignments.stream()
                    .filter(assignment -> assignment.shiftAssignmentId() == shiftAssignmentId).findFirst();
        }

        @Override
        public List<AssignmentView> findAssignmentsByIds(Collection<Long> shiftAssignmentIds) {
            calls.add("findAssignmentsByIds");
            return assignments.stream()
                    .filter(assignment -> shiftAssignmentIds.contains(assignment.shiftAssignmentId())).toList();
        }

        @Override
        public Optional<EmployeeView> findEmployee(long employeeId) {
            calls.add("findEmployee");
            return employees.stream().filter(employee -> employee.employeeId() == employeeId).findFirst();
        }

        @Override
        public List<EmployeeView> findEmployees(Collection<Long> employeeIds) {
            calls.add("findEmployees");
            return employees.stream().filter(employee -> employeeIds.contains(employee.employeeId())).toList();
        }

        @Override
        public List<SwapView> findSwaps(SwapStatus status) {
            calls.add("findSwaps");
            return swaps.stream().filter(swap -> status == null || swap.status() == status)
                    .sorted(Comparator.comparing(SwapView::requestDatetime).reversed()).toList();
        }

        @Override
        public List<SwapView> findSwapsOfEmployee(long employeeId) {
            calls.add("findSwapsOfEmployee");
            return swaps.stream().filter(swap -> swap.involves(employeeId))
                    .sorted(Comparator.comparing(SwapView::requestDatetime).reversed()).toList();
        }

        @Override
        public List<SuggestionView> findSuggestions(long shiftId) {
            calls.add("findSuggestions");
            return suggestionsOf(List.of(shiftId));
        }

        @Override
        public List<SuggestionView> findSuggestionsOfShifts(Collection<Long> shiftIds) {
            calls.add("findSuggestionsOfShifts");
            return suggestionsOf(shiftIds);
        }

        private List<SuggestionView> suggestionsOf(Collection<Long> shiftIds) {
            return suggestions.stream().filter(suggestion -> shiftIds.contains(suggestion.shiftId()))
                    .sorted(Comparator.comparing(SuggestionView::shiftId).thenComparing(SuggestionView::jobRoleId))
                    .toList();
        }
    }
}
