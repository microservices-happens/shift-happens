package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.scheduling.domain.AssignmentStatus;
import dk.ek.shift_happens.scheduling.domain.AssignmentView;
import dk.ek.shift_happens.scheduling.domain.EmployeeView;
import dk.ek.shift_happens.scheduling.domain.RankedCandidate;
import dk.ek.shift_happens.scheduling.domain.ReadModelProjection;
import dk.ek.shift_happens.scheduling.domain.ReadModelQueries;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleSnapshot;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import dk.ek.shift_happens.scheduling.domain.ShiftView;
import dk.ek.shift_happens.scheduling.domain.SuggestionSource;
import dk.ek.shift_happens.scheduling.domain.SuggestionView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The batching of the nested GraphQL fields (P2.11) against a real MongoDB: the number of reads the
 * server executes for the schedule query is the same for 3 shifts and for 20, and the batch reads of
 * {@code MongoReadModel} return what its single reads return, tombstones included.
 *
 * <p>The reads are counted by MongoDB itself ({@code serverStatus.opcounters}: {@code query} is the
 * number of find commands, {@code getmore} the number of further cursor batches), so nothing in the
 * service has to be instrumented. The data stays below the 101 documents of a first cursor batch.
 * {@code GraphQlBatchingTest} pins the same on a counting port, without containers.
 */
class GraphQlRoundTripsIT extends AbstractQueryIT {

    private static final Instant MONDAY = Instant.parse("2027-04-05T00:00:00Z");
    private static final long EMPLOYEE = 3;
    private static final String WEEK = """
            { shifts(from: "%s", to: "%s") {
                shiftId shiftName status isUnderstaffed
                requiredJobRoles { jobRoleId requiredEmployeeCount assignedCount }
                assignments { shiftAssignmentId status employee { employeeId firstName lastName } }
              } }
            """.formatted(MONDAY, MONDAY.plus(Duration.ofDays(7)));

    @Autowired
    private ReadModelProjection writes;
    @Autowired
    private ReadModelQueries readModel;

    @Test
    void theWeekOfAManagerIsFiveReadsForThreeShiftsAndForTwenty() {
        // The shifts; the assignments and employees behind requiredJobRoles and isUnderstaffed; the
        // assignments field; the employees of all assignments.
        assertThat(readsOfTheWeek(3, TestJwks.manager(), 3)).isEqualTo(5);
        assertThat(readsOfTheWeek(20, TestJwks.manager(), 20)).isEqualTo(5);
    }

    @Test
    void theWeekOfAnEmployeeIsSixReadsHoweverManyShiftsTheyWork() {
        // As for the manager, plus the read of their own assignments that selects their shifts.
        assertThat(readsOfTheWeek(3, TestJwks.employee(EMPLOYEE), 3)).isEqualTo(6);
        assertThat(readsOfTheWeek(20, TestJwks.employee(EMPLOYEE), 20)).isEqualTo(6);
    }

    @Test
    void theBatchReadsReturnWhatTheSingleReadsReturnTombstonesIncluded() {
        seed(4);
        // Deleted after a snapshot: still a (deleted) view. Deleted before any snapshot: not a view.
        writes.deleteShift(2, 2, MONDAY);
        writes.deleteShift(70, 1, MONDAY);
        writes.deleteAssignment(assignmentId(3, 1), 2, MONDAY);
        writes.deleteAssignment(7_000, 1, MONDAY);
        writes.deleteEmployee(5, 2, MONDAY);
        writes.deleteEmployee(70, 1, MONDAY);
        List<Long> shiftIds = List.of(1L, 2L, 3L, 4L, 70L, 71L);
        List<Long> assignmentIds = List.of(assignmentId(1, 0), assignmentId(3, 1), assignmentId(4, 3), 7_000L, 7_001L);
        List<Long> employeeIds = List.of(1L, 5L, 9L, 70L, 71L);

        assertThat(readModel.findShiftsByIds(shiftIds)).containsExactlyInAnyOrderElementsOf(
                shiftIds.stream().map(readModel::findShift).flatMap(Optional::stream).toList());
        assertThat(readModel.findShiftsByIds(shiftIds)).extracting(ShiftView::shiftId)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        assertThat(readModel.findAssignmentsByIds(assignmentIds)).containsExactlyInAnyOrderElementsOf(
                assignmentIds.stream().map(readModel::findAssignment).flatMap(Optional::stream).toList());
        assertThat(readModel.findEmployees(employeeIds)).containsExactlyInAnyOrderElementsOf(
                employeeIds.stream().map(readModel::findEmployee).flatMap(Optional::stream).toList());
        assertThat(readModel.findEmployees(employeeIds)).extracting(EmployeeView::employeeId)
                .containsExactlyInAnyOrder(1L, 5L, 9L);

        // The use cases on top: per parent, the batch gives what the single read gives, in its order.
        for (long shiftId : shiftIds) {
            assertThat(queries.assignmentsOfShifts(shiftIds).get(shiftId)).as("assignments of shift %d", shiftId)
                    .isEqualTo(queries.assignmentsOfShift(shiftId));
            assertThat(queries.suggestionsOfShifts(shiftIds).get(shiftId)).as("suggestions of shift %d", shiftId)
                    .isEqualTo(queries.suggestionsOfShift(shiftId));
            assertThat(queries.shiftsById(shiftIds).get(shiftId)).as("shift %d", shiftId)
                    .isEqualTo(queries.shiftOf(shiftId));
        }
        assertThat(queries.assignmentsOfShifts(shiftIds).get(3L)).hasSize(3);
        assertThat(queries.suggestionsOfShifts(shiftIds).get(1L)).extracting(SuggestionView::jobRoleId)
                .containsExactly(1L, 2L);
        assertThat(queries.shiftsById(shiftIds)).containsOnlyKeys(1L, 3L, 4L);
        for (long assignmentId : assignmentIds) {
            assertThat(queries.assignmentsById(assignmentIds).get(assignmentId))
                    .isEqualTo(queries.assignment(assignmentId));
        }
        for (long employeeId : employeeIds) {
            assertThat(queries.employeesById(employeeIds).get(employeeId)).isEqualTo(queries.employee(employeeId));
        }
        assertThat(queries.employeesById(employeeIds)).containsOnlyKeys(1L, 9L);
        List<ShiftView> stored = readModel.findShiftsByIds(shiftIds);
        for (ShiftView shift : stored) {
            assertThat(queries.requiredJobRoles(stored).get(shift.shiftId())).as("staffing of %d", shift.shiftId())
                    .isEqualTo(queries.requiredJobRoles(shift.shiftId()));
        }
    }

    /** Seeds the shifts, answers the query once to warm up, then counts the reads of a second answer. */
    private long readsOfTheWeek(int shifts, String token, int expectedShifts) {
        clearReadModel();
        seed(shifts);
        assertThat(week(token)).hasSize(expectedShifts);
        long before = mongoReads();
        JsonNode answer = week(token);
        long reads = mongoReads() - before;
        assertThat(answer).hasSize(expectedShifts);
        assertThat(answer.findValues("shiftAssignmentId")).hasSize(shifts * 4);
        return reads;
    }

    private JsonNode week(String token) {
        ResponseEntity<String> response = http.postForEntity("/graphql",
                new HttpEntity<>(Map.of("query", WEEK), authorized(token)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            JsonNode body = objectMapper.readTree(response.getBody());
            assertThat(body.path("errors")).as("GraphQL errors: %s", body).isEmpty();
            return body.path("data").path("shifts");
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            throw new IllegalStateException("the GraphQL response is not JSON: " + response.getBody(), notJson);
        }
    }

    /**
     * Nine employees and, per shift, two required roles, four assignments (the first one always for
     * {@link #EMPLOYEE}, so every shift is theirs) and two suggestions. Written through the projection
     * port, so the documents have the shape the projection gives them.
     */
    private void seed(int shifts) {
        for (long id = 1; id <= 9; id++) {
            writes.upsertEmployee(new EmployeeView(id, "First" + id, "Last" + id,
                    id % 2 == 0 ? List.of(1L, 2L) : List.of(2L), "ACTIVE", 37, 1, null));
        }
        for (long shiftId = 1; shiftId <= shifts; shiftId++) {
            Instant start = MONDAY.plus(Duration.ofHours(6 + shiftId));
            Instant end = start.plus(Duration.ofHours(8));
            writes.upsertShift(new ShiftView(shiftId, 2, 3, "Shift " + shiftId, start, end, ShiftStatus.PUBLISHED,
                    List.of(new RequiredJobRoleSnapshot(1, 1), new RequiredJobRoleSnapshot(2, 2)), 1, null));
            for (int seat = 0; seat < 4; seat++) {
                long employeeId = seat == 0 ? EMPLOYEE : (EMPLOYEE + shiftId + seat) % 9 + 1;
                writes.upsertAssignment(new AssignmentView(assignmentId(shiftId, seat), shiftId, employeeId,
                        seat == 2 ? AssignmentStatus.CANCELLED : AssignmentStatus.ASSIGNED, MONDAY, null, null,
                        start, end, 1, null));
            }
            for (long jobRoleId : List.of(2L, 1L)) {
                writes.upsertSuggestion(new SuggestionView(shiftId, jobRoleId, SuggestionSource.RULE, null, MONDAY,
                        "ranked by free hours", List.of(new RankedCandidate(shiftId % 9 + 1, 1))));
            }
        }
    }

    private static long assignmentId(long shiftId, int seat) {
        return shiftId * 10 + seat;
    }
}
