package dk.ek.shift_happens.scheduling;

import dk.ek.shift_happens.scheduling.application.Viewer;
import dk.ek.shift_happens.scheduling.domain.AssignmentView;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleView;
import dk.ek.shift_happens.scheduling.domain.ShiftView;
import dk.ek.shift_happens.scheduling.domain.SuggestionView;
import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The Mongo read model (P2.10): it is built from events, it does not depend on arrival order, it keeps
 * tombstones, it applies a redelivered event once, and it can be rebuilt from its own event log.
 */
class SchedulingProjectionIT extends AbstractQueryIT {

    private static final Instant START = Instant.parse("2027-03-01T06:00:00Z");
    private static final Instant END = Instant.parse("2027-03-01T14:00:00Z");

    @Test
    void theReadModelIsBuiltFromEventsOnly() {
        long shiftId = 7001;
        publish("scheduling.shift.created.v1", shiftId, 1, "scheduling-service",
                shiftPayload(shiftId, "Early", "PUBLISHED", 3));
        publish("workforce.employee.created.v1", 101, 1, "workforce-service", employeePayload(101, "Ada", 7L));
        publish("workforce.employee.created.v1", 102, 1, "workforce-service", employeePayload(102, "Bo", 7L));
        publish("scheduling.assignment.created.v1", 501, 1, "scheduling-service",
                assignmentPayload(501, shiftId, 101));
        publish("scheduling.assignment.created.v1", 502, 1, "scheduling-service",
                assignmentPayload(502, shiftId, 102));
        publish("ai.replacement.suggested.v1", shiftId, 1, "ai-service", suggestionPayload(shiftId, 7L));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.assignmentsOfShift(shiftId)).hasSize(2));

        ShiftView shift = queries.shiftOf(shiftId);
        assertThat(shift.shiftName()).isEqualTo("Early");
        assertThat(shift.status().name()).isEqualTo("PUBLISHED");
        assertThat(shift.requiredJobRoles()).hasSize(1);

        List<RequiredJobRoleView> staffing = queries.requiredJobRoles(shiftId);
        assertThat(staffing).hasSize(1);
        assertThat(staffing.get(0).assignedCount()).isEqualTo(2);
        assertThat(staffing.get(0).understaffed()).isTrue();

        List<SuggestionView> suggestions = queries.suggestionsOfShift(shiftId);
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).source().name()).isEqualTo("LLM");
        assertThat(suggestions.get(0).candidates()).extracting("employeeId").containsExactly(102L, 101L);
    }

    @Test
    void anOlderSnapshotNeverOverwritesANewerOne() {
        long shiftId = 7002;
        publish("scheduling.shift.updated.v1", shiftId, 2, "scheduling-service",
                shiftPayload(shiftId, "Second", "PUBLISHED", 2));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.shiftOf(shiftId).aggregateVersion()).isEqualTo(2));

        publish("scheduling.shift.updated.v1", shiftId, 1, "scheduling-service",
                shiftPayload(shiftId, "First", "PLANNED", 2));

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            ShiftView shift = queries.shiftOf(shiftId);
            assertThat(shift.shiftName()).isEqualTo("Second");
            assertThat(shift.aggregateVersion()).isEqualTo(2);
        });
    }

    @Test
    void aTombstoneIsRetainedAndWinsOverALaterSnapshot() {
        long shiftId = 7003;
        publish("scheduling.shift.created.v1", shiftId, 1, "scheduling-service",
                shiftPayload(shiftId, "Doomed", "PLANNED", 1));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.shiftOf(shiftId)).isNotNull());

        publish("scheduling.shift.deleted.v1", shiftId, 2, "scheduling-service",
                Map.of("entityType", "shift", "entityId", shiftId, "deletedAt", Instant.now().toString()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.shiftOf(shiftId)).isNull());

        // A late snapshot with a higher version still may not resurrect the shift.
        publish("scheduling.shift.updated.v1", shiftId, 3, "scheduling-service",
                shiftPayload(shiftId, "Resurrected", "PLANNED", 1));

        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.shiftOf(shiftId)).isNull());
        Document stored = mongo.findById(shiftId, Document.class, "shifts");
        assertThat(stored.get("deletedAt")).isNotNull();
        assertThat(stored.get("shiftName")).isEqualTo("Doomed");
    }
    @Test
    void aRedeliveredEventIsAppliedOnce() {
        long shiftId = 7004;
        publishTwice("scheduling.shift.created.v1", shiftId, 1, "scheduling-service",
                shiftPayload(shiftId, "Once", "PLANNED", 1));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(count("processed_events")).isEqualTo(1));
        assertThat(count("shifts")).isEqualTo(1);
        assertThat(count("projection_events")).isEqualTo(1);
    }

    @Test
    void theRebuildReplaysEveryLoggedEventIntoEmptyCollections() {
        long shiftId = 7005;
        publish("scheduling.shift.created.v1", shiftId, 1, "scheduling-service",
                shiftPayload(shiftId, "Replayable", "PUBLISHED", 2));
        publish("workforce.employee.created.v1", 105, 1, "workforce-service", employeePayload(105, "Cy", 7L));
        publish("scheduling.assignment.created.v1", 505, 1, "scheduling-service",
                assignmentPayload(505, shiftId, 105));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.assignmentsOfShift(shiftId)).hasSize(1));

        long loggedEvents = count("projection_events");
        assertThat(loggedEvents).isEqualTo(3);

        long replayed = projection.rebuild();

        assertThat(replayed).isEqualTo(loggedEvents);
        assertThat(queries.shiftOf(shiftId).shiftName()).isEqualTo("Replayable");
        assertThat(queries.assignmentsOfShift(shiftId)).hasSize(1);
        assertThat(queries.employee(105).fullName()).isEqualTo("Cy Lovelace");
        // The log survives the rebuild, so it can be replayed again.
        assertThat(count("projection_events")).isEqualTo(loggedEvents);
        assertThat(count("processed_events")).isEqualTo(loggedEvents);
    }

    @Test
    void theScheduleIsQueriedByEmployeeAndWindow() {
        long shiftId = 7006;
        publish("scheduling.shift.created.v1", shiftId, 1, "scheduling-service",
                shiftPayload(shiftId, "Mine", "PUBLISHED", 1));
        publish("workforce.employee.created.v1", 106, 1, "workforce-service", employeePayload(106, "Di", 7L));
        publish("scheduling.assignment.created.v1", 506, 1, "scheduling-service",
                assignmentPayload(506, shiftId, 106));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.employeeSchedule(new Viewer(106L, "Employee"), 106,
                        START.minusSeconds(3600), END.plusSeconds(3600))).hasSize(1));

        assertThat(queries.employeeSchedule(new Viewer(106L, "Employee"), 106, END.plusSeconds(7200),
                END.plusSeconds(9000))).isEmpty();

        List<AssignmentView> found = queries.employeeSchedule(new Viewer(1L, "Manager"), 106,
                START.minusSeconds(3600), END.plusSeconds(3600));
        assertThat(found).extracting(AssignmentView::shiftAssignmentId).containsExactly(506L);
    }

    private static Map<String, Object> shiftPayload(long shiftId, String name, String status, int required) {
        return Map.of(
                "shiftId", shiftId,
                "departmentId", 2,
                "workLocationId", 3,
                "shiftName", name,
                "startDatetime", START.toString(),
                "endDatetime", END.toString(),
                "shiftStatus", status,
                "requiredJobRoles", List.of(Map.of("jobRoleId", 7, "requiredEmployeeCount", required)));
    }

    private static Map<String, Object> assignmentPayload(long assignmentId, long shiftId, long employeeId) {
        return Map.of(
                "shiftAssignmentId", assignmentId,
                "shiftId", shiftId,
                "employeeId", employeeId,
                "assignmentStatus", "ASSIGNED",
                "assignedDatetime", Instant.now().toString(),
                "shiftStartDatetime", START.toString(),
                "shiftEndDatetime", END.toString());
    }

    private static Map<String, Object> employeePayload(long employeeId, String firstName, Long jobRoleId) {
        return Map.of(
                "employeeId", employeeId,
                "employeeNumber", "EMP-" + employeeId,
                "userRole", "Employee",
                "firstName", firstName,
                "lastName", "Lovelace",
                "email", "emp" + employeeId + "@shift.dk",
                "employmentStatus", "ACTIVE",
                "jobRoleIds", List.of(jobRoleId),
                "weeklyContractHours", 37.0);
    }

    private static Map<String, Object> suggestionPayload(long shiftId, long jobRoleId) {
        return Map.of(
                "shiftId", shiftId,
                "jobRoleId", jobRoleId,
                "rankedCandidates", List.of(
                        Map.of("employeeId", 102, "rank", 1),
                        Map.of("employeeId", 101, "rank", 2)),
                "reason", "Bo has the fewest hours this week.",
                "source", "llm",
                "model", "llama3.2:1b");
    }
}
