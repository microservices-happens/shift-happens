package dk.ek.shift_happens.scheduling;

import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3.3: the Mongo projection is a commutative message handler. One fixed set of events (shifts,
 * assignments, employees, swaps and AI suggestions, with updates and tombstones) is applied to the real
 * read model in the original order, reversed, in seeded shuffles and with redeliveries; the stored
 * documents must be identical every time.
 *
 * <p>The envelopes go straight into {@code SchedulingProjectionService.apply}, the method the listener
 * calls, so the test decides the arrival order itself instead of hoping the broker scrambles it. The
 * histories are what the producers can publish: versions count up from 1 and a tombstone, if there is
 * one, is the last event of its entity. The two suggestions of the same shift and role have different
 * timestamps, because "newest suggestedAt wins" has no tie-break.
 */
class SchedulingProjectionShuffledOrderIT extends AbstractQueryIT {

    private static final Instant T0 = Instant.parse("2027-03-01T00:00:00Z");
    private static final Instant START = Instant.parse("2027-03-01T06:00:00Z");
    private static final Instant END = Instant.parse("2027-03-01T14:00:00Z");
    private static final List<String> READ_MODEL = List.of("shifts", "assignments", "employees", "swaps",
            "suggestions");

    private static final long LIVE_SHIFT = 7101;
    private static final long DELETED_SHIFT = 7102;
    private static final long LIVE_ASSIGNMENT = 5101;
    private static final long DELETED_ASSIGNMENT = 5102;
    private static final long LIVE_EMPLOYEE = 1101;
    private static final long DELETED_EMPLOYEE = 1102;
    private static final long SWAP = 9101;

    @Test
    void everySeededShuffleGivesTheSameReadModel() {
        List<EventEnvelope> events = scenario();
        Map<String, List<Document>> expected = applyToAnEmptyReadModel(events);
        assertTheScenarioEndedWhereItShould();

        List<EventEnvelope> reversed = new ArrayList<>(events);
        Collections.reverse(reversed);
        assertThat(applyToAnEmptyReadModel(reversed)).as("reverse order").isEqualTo(expected);

        for (long seed = 1; seed <= 25; seed++) {
            List<EventEnvelope> shuffled = new ArrayList<>(events);
            Collections.shuffle(shuffled, new Random(seed));

            assertThat(applyToAnEmptyReadModel(shuffled)).as("seed %d: %s", seed, describe(shuffled))
                    .isEqualTo(expected);
        }
    }

    @Test
    void redeliveriesInAnyOrderChangeNothing() {
        List<EventEnvelope> events = scenario();
        Map<String, List<Document>> expected = applyToAnEmptyReadModel(events);

        for (long seed = 1; seed <= 10; seed++) {
            Random random = new Random(seed);
            List<EventEnvelope> noisy = new ArrayList<>(events);
            // Redelivery: the same envelope (same eventId) arrives a second or third time.
            for (EventEnvelope event : events) {
                for (int extra = random.nextInt(3); extra > 0; extra--) {
                    noisy.add(event);
                }
            }
            Collections.shuffle(noisy, random);

            assertThat(applyToAnEmptyReadModel(noisy)).as("seed %d: %s", seed, describe(noisy))
                    .isEqualTo(expected);
            assertThat(count("projection_events")).as("every event is logged once").isEqualTo(events.size());
            assertThat(count("processed_events")).isEqualTo(events.size());
        }
    }

    @Test
    void theVersionRuleAloneMakesAReapplyHarmless() {
        // processed_events is written after the change, so a crash in between re-applies the event. Forgetting
        // the markers between two passes is that crash for every event at once.
        List<EventEnvelope> events = scenario();
        Map<String, List<Document>> expected = applyToAnEmptyReadModel(events);

        mongo.remove(new Query(), "processed_events");
        List<EventEnvelope> again = new ArrayList<>(events);
        Collections.shuffle(again, new Random(7));
        again.forEach(projection::apply);

        assertThat(readModel()).isEqualTo(expected);
    }

    @Test
    void aTombstoneThatArrivesBeforeTheCreateStillWins() {
        // The smallest out-of-order case, spelled out for each entity that can be deleted.
        projection.apply(tombstone("scheduling.shift.deleted.v1", "shift", DELETED_SHIFT, 2));
        projection.apply(tombstone("scheduling.assignment.deleted.v1", "assignment", DELETED_ASSIGNMENT, 2));
        projection.apply(tombstone("workforce.employee.deleted.v1", "employee", DELETED_EMPLOYEE, 2));
        assertThat(queries.shiftOf(DELETED_SHIFT)).as("a tombstone without a snapshot is not a shift").isNull();
        assertThat(queries.assignment(DELETED_ASSIGNMENT)).isNull();
        assertThat(queries.employee(DELETED_EMPLOYEE)).isNull();

        projection.apply(shift("scheduling.shift.created.v1", DELETED_SHIFT, 1, "Late create", "PLANNED"));
        projection.apply(assignment("scheduling.assignment.created.v1", DELETED_ASSIGNMENT, 1, DELETED_SHIFT,
                LIVE_EMPLOYEE, "ASSIGNED"));
        projection.apply(employee("workforce.employee.created.v1", DELETED_EMPLOYEE, 1, "Late", 7L));

        assertThat(queries.shiftOf(DELETED_SHIFT)).as("a late create must not resurrect the shift").isNull();
        assertThat(queries.assignment(DELETED_ASSIGNMENT)).isNull();
        assertThat(queries.assignmentsOfShift(DELETED_SHIFT)).isEmpty();
        assertThat(queries.employee(DELETED_EMPLOYEE)).isNull();
        for (Map.Entry<String, Long> deleted : Map.of("shifts", DELETED_SHIFT, "assignments", DELETED_ASSIGNMENT,
                "employees", DELETED_EMPLOYEE).entrySet()) {
            Document stored = mongo.findById(deleted.getValue(), Document.class, deleted.getKey());
            assertThat(stored.get("deletedAt")).as("%s tombstone", deleted.getKey()).isNotNull();
            assertThat(stored.get("aggregateVersion")).as("%s version", deleted.getKey()).isEqualTo(2L);
        }
    }

    // ---- the scenario ----

    private static List<EventEnvelope> scenario() {
        List<EventEnvelope> events = new ArrayList<>();
        // A shift that is edited twice and stays.
        events.add(shift("scheduling.shift.created.v1", LIVE_SHIFT, 1, "Early", "PLANNED"));
        events.add(shift("scheduling.shift.updated.v1", LIVE_SHIFT, 2, "Early (renamed)", "PLANNED"));
        events.add(shift("scheduling.shift.updated.v1", LIVE_SHIFT, 3, "Early (published)", "PUBLISHED"));
        // A shift that is edited and then deleted.
        events.add(shift("scheduling.shift.created.v1", DELETED_SHIFT, 1, "Doomed", "PLANNED"));
        events.add(shift("scheduling.shift.updated.v1", DELETED_SHIFT, 2, "Doomed (renamed)", "PLANNED"));
        events.add(tombstone("scheduling.shift.deleted.v1", "shift", DELETED_SHIFT, 3));
        // Employees: one updated, one updated and then deleted.
        events.add(employee("workforce.employee.created.v1", LIVE_EMPLOYEE, 1, "Ada", 7L));
        events.add(employee("workforce.employee.updated.v1", LIVE_EMPLOYEE, 2, "Ada-Marie", 8L));
        events.add(employee("workforce.employee.created.v1", DELETED_EMPLOYEE, 1, "Bo", 7L));
        events.add(employee("workforce.employee.updated.v1", DELETED_EMPLOYEE, 2, "Bob", 7L));
        events.add(tombstone("workforce.employee.deleted.v1", "employee", DELETED_EMPLOYEE, 3));
        // Assignments: one approved, one cancelled and then deleted.
        events.add(assignment("scheduling.assignment.created.v1", LIVE_ASSIGNMENT, 1, LIVE_SHIFT, LIVE_EMPLOYEE,
                "ASSIGNED"));
        events.add(assignment("scheduling.assignment.updated.v1", LIVE_ASSIGNMENT, 2, LIVE_SHIFT, LIVE_EMPLOYEE,
                "APPROVED"));
        events.add(assignment("scheduling.assignment.created.v1", DELETED_ASSIGNMENT, 1, LIVE_SHIFT,
                DELETED_EMPLOYEE, "ASSIGNED"));
        events.add(assignment("scheduling.assignment.updated.v1", DELETED_ASSIGNMENT, 2, LIVE_SHIFT,
                DELETED_EMPLOYEE, "CANCELLED"));
        events.add(tombstone("scheduling.assignment.deleted.v1", "assignment", DELETED_ASSIGNMENT, 3));
        // A swap that is requested and then approved.
        events.add(swap("scheduling.swap.requested.v1", 1, "PENDING", null));
        events.add(swap("scheduling.swap.approved.v1", 2, "APPROVED", Map.of(
                "approverEmployeeId", 1, "decision", "APPROVED", "comment", "ok",
                "decisionDatetime", T0.plusSeconds(900).toString())));
        // Suggestions: two for the same shift and role (the newer one wins) and one for another role.
        events.add(suggestion(1, 7, T0.plusSeconds(100), List.of(LIVE_EMPLOYEE, DELETED_EMPLOYEE), "first"));
        events.add(suggestion(2, 7, T0.plusSeconds(200), List.of(DELETED_EMPLOYEE, LIVE_EMPLOYEE), "second"));
        events.add(suggestion(1, 8, T0.plusSeconds(150), List.of(LIVE_EMPLOYEE), "other role"));
        return events;
    }

    /** Facts about the end state, so "the same in every order" cannot mean "the same wrong state". */
    private void assertTheScenarioEndedWhereItShould() {
        assertThat(queries.shiftOf(LIVE_SHIFT).shiftName()).isEqualTo("Early (published)");
        assertThat(queries.shiftOf(LIVE_SHIFT).aggregateVersion()).isEqualTo(3);
        assertThat(queries.shiftOf(DELETED_SHIFT)).isNull();
        Document deletedShift = mongo.findById(DELETED_SHIFT, Document.class, "shifts");
        assertThat(deletedShift.get("deletedAt")).isNotNull();
        assertThat(deletedShift.get("aggregateVersion")).isEqualTo(3L);
        assertThat(deletedShift.get("shiftName")).as("the last snapshot before the delete")
                .isEqualTo("Doomed (renamed)");

        assertThat(queries.employee(LIVE_EMPLOYEE).firstName()).isEqualTo("Ada-Marie");
        assertThat(queries.employee(DELETED_EMPLOYEE)).isNull();
        assertThat(mongo.findById(DELETED_EMPLOYEE, Document.class, "employees").get("firstName"))
                .as("the last snapshot before the delete").isEqualTo("Bob");

        assertThat(queries.assignmentsOfShift(LIVE_SHIFT)).extracting("shiftAssignmentId")
                .containsExactly(LIVE_ASSIGNMENT);
        assertThat(queries.assignment(LIVE_ASSIGNMENT).status().name()).isEqualTo("APPROVED");
        assertThat(queries.assignment(DELETED_ASSIGNMENT)).isNull();

        assertThat(mongo.findById(SWAP, Document.class, "swaps").get("swapStatus")).isEqualTo("APPROVED");
        assertThat(queries.suggestionsOfShift(LIVE_SHIFT)).extracting("reason")
                .containsExactly("second", "other role");
    }

    // ---- harness ----

    /** Empties everything (read model, markers and log), applies the events in this order, returns the result. */
    private Map<String, List<Document>> applyToAnEmptyReadModel(List<EventEnvelope> order) {
        clearReadModel();
        order.forEach(projection::apply);
        // The log and the markers hold the same eventIds whatever the order was.
        TreeSet<String> logged = new TreeSet<>();
        mongo.findAll(Document.class, "projection_events").forEach(event -> logged.add(event.getString("_id")));
        TreeSet<String> expectedIds = new TreeSet<>();
        order.forEach(event -> expectedIds.add(event.eventId().toString()));
        assertThat(logged).as("projection_events").isEqualTo(expectedIds);
        return readModel();
    }

    /** Every document of every read-model collection, sorted by id. */
    private Map<String, List<Document>> readModel() {
        Map<String, List<Document>> state = new LinkedHashMap<>();
        for (String collection : READ_MODEL) {
            state.put(collection, mongo.find(new Query().with(Sort.by("_id")), Document.class, collection));
        }
        return state;
    }

    private static EventEnvelope shift(String eventType, long shiftId, long version, String name, String status) {
        return envelope(eventType, "scheduling-service", shiftId, version, Map.of(
                "shiftId", shiftId,
                "departmentId", 2,
                "workLocationId", 3,
                "shiftName", name,
                "startDatetime", START.toString(),
                "endDatetime", END.toString(),
                "shiftStatus", status,
                "requiredJobRoles", List.of(Map.of("jobRoleId", 7, "requiredEmployeeCount", (int) version))));
    }

    private static EventEnvelope assignment(String eventType, long assignmentId, long version, long shiftId,
                                            long employeeId, String status) {
        return envelope(eventType, "scheduling-service", assignmentId, version, Map.of(
                "shiftAssignmentId", assignmentId,
                "shiftId", shiftId,
                "employeeId", employeeId,
                "assignmentStatus", status,
                "assignedDatetime", T0.plusSeconds(60).toString(),
                "shiftStartDatetime", START.toString(),
                "shiftEndDatetime", END.toString()));
    }

    private static EventEnvelope employee(String eventType, long employeeId, long version, String firstName,
                                          long jobRoleId) {
        return envelope(eventType, "workforce-service", employeeId, version, Map.of(
                "employeeId", employeeId,
                "employeeNumber", "EMP-" + employeeId,
                "userRole", "Employee",
                "firstName", firstName,
                "lastName", "Lovelace",
                "email", "emp" + employeeId + "@shift.dk",
                "employmentStatus", "ACTIVE",
                "jobRoleIds", List.of(jobRoleId),
                "weeklyContractHours", 30.0 + version));
    }

    private static EventEnvelope swap(String eventType, long version, String status, Map<String, Object> decision) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("shiftSwapId", SWAP);
        data.put("originalShiftAssignmentId", LIVE_ASSIGNMENT);
        data.put("shiftId", LIVE_SHIFT);
        data.put("employeeFromId", LIVE_EMPLOYEE);
        data.put("employeeToId", DELETED_EMPLOYEE);
        data.put("swapStatus", status);
        data.put("reason", "family");
        data.put("requestDatetime", T0.plusSeconds(300).toString());
        data.put("decision", decision);
        return envelope(eventType, "scheduling-service", SWAP, version, data);
    }

    private static EventEnvelope suggestion(long version, long jobRoleId, Instant suggestedAt, List<Long> ranked,
                                            String reason) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            candidates.add(Map.of("employeeId", ranked.get(i), "rank", i + 1));
        }
        return new EventEnvelope(UUID.randomUUID(), "ai.replacement.suggested.v1", suggestedAt, "ai-service",
                LIVE_SHIFT, version, "corr-suggestion", null, Map.of(
                        "shiftId", LIVE_SHIFT,
                        "jobRoleId", jobRoleId,
                        "rankedCandidates", candidates,
                        "reason", reason,
                        "source", "llm",
                        "model", "llama3.2:1b"));
    }

    private static EventEnvelope tombstone(String eventType, String entityType, long entityId, long version) {
        return envelope(eventType, eventType.startsWith("workforce") ? "workforce-service" : "scheduling-service",
                entityId, version, Map.of("entityType", entityType, "entityId", entityId,
                        "deletedAt", T0.plusSeconds(1000 + version).toString()));
    }

    private static EventEnvelope envelope(String eventType, String producer, long aggregateId, long version,
                                          Object data) {
        return new EventEnvelope(UUID.randomUUID(), eventType, T0.plusSeconds(version), producer, aggregateId,
                version, "corr-" + aggregateId, null, data);
    }

    private static String describe(List<EventEnvelope> order) {
        StringBuilder text = new StringBuilder();
        for (EventEnvelope event : order) {
            String[] parts = event.eventType().split("\\.");
            text.append(parts[1]).append(event.aggregateId())
                    .append(event.eventType().endsWith(".deleted.v1") ? ":T" : ":S")
                    .append(event.aggregateVersion()).append(' ');
        }
        return text.toString().trim();
    }
}
