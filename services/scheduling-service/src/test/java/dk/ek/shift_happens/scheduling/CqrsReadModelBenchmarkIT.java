package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaxxer.hikari.HikariDataSource;
import dk.ek.shift_happens.scheduling.application.Viewer;
import dk.ek.shift_happens.scheduling.domain.AssignmentStatus;
import dk.ek.shift_happens.scheduling.domain.AssignmentView;
import dk.ek.shift_happens.scheduling.domain.EmployeeView;
import dk.ek.shift_happens.scheduling.domain.ReadModelProjection;
import dk.ek.shift_happens.scheduling.domain.ReadModelQueries;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleSnapshot;
import dk.ek.shift_happens.scheduling.domain.RequiredJobRoleView;
import dk.ek.shift_happens.scheduling.domain.ShiftStatus;
import dk.ek.shift_happens.scheduling.domain.ShiftView;
import dk.ek.shift_happens.scheduling.domain.UnderstaffingRules;
import dk.ek.shift_happens.scheduling.infrastructure.persistence.LongListConverter;
import org.bson.Document;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3.6: measures the schedule question "one department's week, with staffing and names" answered from the
 * Mongo read model and from the normalised command-side Postgres tables, on the same data.
 *
 * <p>Opt-in: it only runs with {@code -Dcqrs.benchmark=true} (off by default in the pom), because it
 * takes a minute or two and asserts no latency. In CI: run the {@code ci} workflow by hand with
 * {@code benchmark} ticked.
 *
 * <p>This is a measurement, not a gate: the assertions are that all answers are equal and that the
 * deployed endpoint makes the round trips of the variant that stands for it. No latency is asserted, so
 * a noisy CI runner cannot fail the build. Method, limits and the numbers CI printed are in
 * docs/measurements/cqrs-read-model.md.
 *
 * <p>The ways of answering:
 * <ul>
 *   <li><b>read model, per parent</b>: one use-case call per shift and per assignment, which is how the
 *       GraphQL resolvers read until the nested fields were batched. No longer deployed; kept so the
 *       same run shows before and after.</li>
 *   <li><b>read model, batched resolvers</b>: the calls the DataLoaders of
 *       {@code SchedulingGraphQlController} make on {@code SchedulingQueryService} for the schedule
 *       query, one per nested field. This is what the query role does today.</li>
 *   <li><b>read model, three reads</b>: the same Mongo collections read once each. Not deployed; it is
 *       the floor for these collections.</li>
 *   <li><b>write model, joins</b>: two SQL statements with joins over the Flyway schema of the command
 *       role, on plain JDBC.</li>
 *   <li><b>deployed query over HTTP</b>: the real request, POST /graphql with a Manager token. It is
 *       the only variant that pays for HTTP, JWT validation, GraphQL execution and JSON, so its time
 *       is not comparable with the others; it is there to show what a client waits for and to check
 *       that the endpoint really makes the round trips of the batched-resolvers variant.</li>
 * </ul>
 *
 * <p>Timed, except for the HTTP variant: the database round trips and the mapping to plain records, in
 * this JVM. Both databases are containers on the same host, so there is no real network between the
 * service and its store. The Mongo round trips in the table are counted by the server (find commands
 * plus getMore), not assumed.
 *
 * <p>Tune with {@code -Dcqrs.benchmark.warmup=N -Dcqrs.benchmark.iterations=N}.
 */
@EnabledIfSystemProperty(named = "cqrs.benchmark", matches = "true")
class CqrsReadModelBenchmarkIT extends AbstractQueryIT {

    private static final int WARMUP = Integer.getInteger("cqrs.benchmark.warmup", 30);
    private static final int ITERATIONS = Integer.getInteger("cqrs.benchmark.iterations", 200);
    private static final String MARKER = "CQRS-BENCH | ";

    private static final String PER_PARENT = "read model (Mongo), per parent (resolvers before batching)";
    private static final String BATCHED_RESOLVERS = "read model (Mongo), batched resolvers (deployed)";
    private static final String THREE_READS = "read model (Mongo), three reads (not deployed)";
    private static final String JOINS = "write model (Postgres), joins";
    private static final String OVER_HTTP = "deployed query over HTTP (JWT, GraphQL, JSON included)";

    // The organisation: 10 departments of 50 employees, three shifts a day per department, five people
    // on a shift. One year of that is 10,950 shifts and 54,750 assignments.
    private static final int DEPARTMENTS = 10;
    private static final int EMPLOYEES_PER_DEPARTMENT = 50;
    private static final int SHIFTS_PER_DAY = 3;
    private static final int ASSIGNMENTS_PER_SHIFT = 5;
    /** Days of history present when each measurement is taken. The data is loaded cumulatively. */
    private static final int[] HISTORY_DAYS = {30, 90, 365};

    private static final Instant EPOCH = Instant.parse("2027-01-04T00:00:00Z");
    /** The question: department 4, the third week. It lies inside every history size. */
    private static final long DEPARTMENT_ID = 4;
    private static final Instant FROM = EPOCH.plus(Duration.ofDays(14));
    private static final Instant TO = EPOCH.plus(Duration.ofDays(21));
    private static final Viewer MANAGER = new Viewer(900L, Viewer.MANAGER);

    /** The schedule query, selecting exactly what a {@link ShiftAnswer} holds. */
    private static final String WEEK_QUERY = """
            { shifts(from: "%s", to: "%s", departmentId: %d) {
                shiftId shiftName departmentId workLocationId startDatetime endDatetime status isUnderstaffed
                requiredJobRoles { jobRoleId requiredEmployeeCount assignedCount }
                assignments { shiftAssignmentId status assignedDatetime employee { employeeId firstName lastName } }
              } }
            """.formatted(FROM, TO, DEPARTMENT_ID);

    /** A test token lives five minutes, so the HTTP variant gets a fresh one for every history size. */
    private String managerToken;

    private static final LongListConverter JOB_ROLE_IDS = new LongListConverter();

    private static PostgreSQLContainer<?> postgres;
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;

    @Autowired
    private ReadModelProjection readModelWrites;
    @Autowired
    private ReadModelQueries readModel;

    /** What the schedule page shows for one shift; all variants must produce exactly this. */
    record ShiftAnswer(long shiftId, String shiftName, long departmentId, long workLocationId, Instant start,
                       Instant end, String status, boolean understaffed, List<RoleAnswer> roles,
                       List<AssignmentAnswer> assignments) {
    }

    record RoleAnswer(long jobRoleId, int requiredEmployeeCount, int assignedCount) {
    }

    record AssignmentAnswer(long shiftAssignmentId, String status, Instant assignedDatetime, long employeeId,
                            String firstName, String lastName) {
    }

    /**
     * Its own Postgres with the real Flyway schema: the query-role context has no datasource, and the
     * database of the command tests is shared, so the benchmark rows must not end up there.
     */
    @BeforeAll
    static void startTheWriteModel() {
        postgres = new PostgreSQLContainer<>("postgres:17-alpine");
        postgres.start();
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        dataSource.setMaximumPoolSize(4);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopTheWriteModel() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    /** The Mongo container is shared with the other query tests. */
    @AfterEach
    void emptyTheReadModel() {
        readModelWrites.clear();
    }

    @Test
    void measuresTheDepartmentWeekOnTheReadModelAndOnTheWriteModel() throws IOException {
        Map<String, Supplier<List<ShiftAnswer>>> variants = new LinkedHashMap<>();
        variants.put(PER_PARENT, this::fromTheReadModelPerParent);
        variants.put(BATCHED_RESOLVERS, this::fromTheReadModelAsResolved);
        variants.put(THREE_READS, this::fromTheReadModelInThreeReads);
        variants.put(JOINS, this::fromTheWriteModelWithJoins);
        variants.put(OVER_HTTP, this::overHttp);

        List<String> report = new ArrayList<>();
        report.add("environment: " + environment());
        report.add("warm-up rounds per size: " + WARMUP + ", measured rounds per size: " + ITERATIONS);
        report.add("");
        report.add("| history | shifts stored | assignments stored | shifts in answer | assignments in answer"
                + " | variant | round trips | p50 ms | p95 ms | mean ms | min ms | max ms |");
        report.add("|---|---|---|---|---|---|---|---|---|---|---|---|");

        loadEmployees();
        int loadedDays = 0;
        for (int days : HISTORY_DAYS) {
            loadDays(loadedDays, days);
            loadedDays = days;
            jdbc.execute("ANALYZE");
            managerToken = TestJwks.manager();

            // Same question, same answer: otherwise the timings below would compare different work.
            List<ShiftAnswer> expected = fromTheWriteModelWithJoins();
            assertThat(expected).isNotEmpty();
            assertThat(expected).allSatisfy(shift -> assertThat(shift.assignments()).isNotEmpty());
            variants.forEach((name, variant) -> assertThat(variant.get()).as(name).isEqualTo(expected));

            // Counted by MongoDB, one answer per variant. Postgres: the two statements of the variant.
            Map<String, Long> roundTrips = new LinkedHashMap<>();
            variants.forEach((name, variant) -> {
                long before = mongoReads();
                variant.get();
                roundTrips.put(name, JOINS.equals(name) ? 2 : mongoReads() - before);
            });
            assertThat(roundTrips.get(OVER_HTTP))
                    .as("the endpoint makes the round trips of the variant that stands for the deployed resolvers")
                    .isEqualTo(roundTrips.get(BATCHED_RESOLVERS));

            for (int round = 0; round < WARMUP; round++) {
                variants.values().forEach(Supplier::get);
            }
            Map<String, long[]> nanos = new LinkedHashMap<>();
            variants.keySet().forEach(name -> nanos.put(name, new long[ITERATIONS]));
            // One round runs every variant once, so drift on the runner hits all of them alike.
            for (int round = 0; round < ITERATIONS; round++) {
                for (Map.Entry<String, Supplier<List<ShiftAnswer>>> variant : variants.entrySet()) {
                    long start = System.nanoTime();
                    variant.getValue().get();
                    nanos.get(variant.getKey())[round] = System.nanoTime() - start;
                }
            }

            int answerAssignments = expected.stream().mapToInt(shift -> shift.assignments().size()).sum();
            for (Map.Entry<String, long[]> measured : nanos.entrySet()) {
                long[] sorted = measured.getValue().clone();
                Arrays.sort(sorted);
                report.add(String.format(Locale.ROOT, "| %d days | %d | %d | %d | %d | %s | %d | %s | %s | %s | %s | %s |",
                        days, rowsIn("shift"), rowsIn("shift_assignment"), expected.size(), answerAssignments,
                        measured.getKey(), roundTrips.get(measured.getKey()),
                        millis(percentile(sorted, 50)), millis(percentile(sorted, 95)),
                        millis((long) Arrays.stream(sorted).average().orElse(0)),
                        millis(sorted[0]), millis(sorted[sorted.length - 1])));
            }
        }
        publishReport(report);
    }

    // ---- variant 1: what the query role did before the nested fields were batched ------------------

    /**
     * One use-case call per parent, as the resolvers made them until P2.11 was batched: per shift
     * requiredJobRoles and isUnderstaffed (3 reads each) and assignments (1 read), then one employee
     * read per assignment.
     */
    private List<ShiftAnswer> fromTheReadModelPerParent() {
        return queries.shifts(MANAGER, FROM, TO, DEPARTMENT_ID, null, null).stream().map(shift -> {
            List<RoleAnswer> roles = queries.requiredJobRoles(shift.shiftId()).stream()
                    .map(role -> new RoleAnswer(role.jobRoleId(), role.requiredEmployeeCount(),
                            role.assignedCount()))
                    .toList();
            boolean understaffed = queries.requiredJobRoles(shift.shiftId()).stream()
                    .anyMatch(RequiredJobRoleView::understaffed);
            List<AssignmentAnswer> assignments = queries.assignmentsOfShift(shift.shiftId()).stream()
                    .map(assignment -> {
                        EmployeeView employee = queries.employee(assignment.employeeId());
                        return new AssignmentAnswer(assignment.shiftAssignmentId(), assignment.status().name(),
                                assignment.assignedDatetime(), employee.employeeId(), employee.firstName(),
                                employee.lastName());
                    })
                    .toList();
            return answer(shift, understaffed, roles, assignments);
        }).toList();
    }

    // ---- variant 2: what the query role does today -------------------------------------------------

    /**
     * The same service calls, in the same number, as the DataLoaders of SchedulingGraphQlController make
     * for {@code shifts { ... isUnderstaffed requiredJobRoles { .. } assignments { .. employee { .. } } }}:
     * the shifts, one staffing batch shared by requiredJobRoles and isUnderstaffed, one assignments
     * batch and one employee batch. The test asserts that the real endpoint makes as many round trips.
     */
    private List<ShiftAnswer> fromTheReadModelAsResolved() {
        List<ShiftView> shifts = queries.shifts(MANAGER, FROM, TO, DEPARTMENT_ID, null, null);
        Map<Long, List<RequiredJobRoleView>> staffing = queries.requiredJobRoles(shifts);
        Map<Long, List<AssignmentView>> assignments = queries.assignmentsOfShifts(
                shifts.stream().map(ShiftView::shiftId).collect(Collectors.toSet()));
        Map<Long, EmployeeView> employees = queries.employeesById(assignments.values().stream()
                .flatMap(List::stream).map(AssignmentView::employeeId).collect(Collectors.toSet()));
        return shifts.stream().map(shift -> {
            List<RequiredJobRoleView> roles = staffing.get(shift.shiftId());
            return answer(shift, roles.stream().anyMatch(RequiredJobRoleView::understaffed),
                    roles.stream().map(role -> new RoleAnswer(role.jobRoleId(), role.requiredEmployeeCount(),
                            role.assignedCount())).toList(),
                    assignments.get(shift.shiftId()).stream().map(assignment -> {
                        EmployeeView employee = employees.get(assignment.employeeId());
                        return new AssignmentAnswer(assignment.shiftAssignmentId(), assignment.status().name(),
                                assignment.assignedDatetime(), employee.employeeId(), employee.firstName(),
                                employee.lastName());
                    }).toList());
        }).toList();
    }

    // ---- variant 3: the same collections read once each --------------------------------------------

    private List<ShiftAnswer> fromTheReadModelInThreeReads() {
        List<ShiftView> shifts = readModel.findShifts(FROM, TO, DEPARTMENT_ID, null, null);
        List<Long> shiftIds = shifts.stream().map(ShiftView::shiftId).toList();
        List<Document> assignments = mongo.find(
                new Query(Criteria.where("shiftId").in(shiftIds).and("deletedAt").is(null))
                        .with(Sort.by("shiftId", "_id")),
                Document.class, "assignments");
        Map<Long, EmployeeView> employees = readModel.findEmployees(assignments.stream()
                        .map(assignment -> assignment.getLong("employeeId")).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(EmployeeView::employeeId, employee -> employee));

        Map<Long, List<Staffed>> staffing = new LinkedHashMap<>();
        for (Document assignment : assignments) {
            EmployeeView employee = employees.get(assignment.getLong("employeeId"));
            String status = assignment.getString("assignmentStatus");
            staffing.computeIfAbsent(assignment.getLong("shiftId"), id -> new ArrayList<>()).add(new Staffed(
                    new AssignmentAnswer(assignment.getLong("_id"), status,
                            assignment.getDate("assignedDatetime").toInstant(), employee.employeeId(),
                            employee.firstName(), employee.lastName()),
                    AssignmentStatus.valueOf(status).isActive(), employee.jobRoleIds()));
        }
        return shifts.stream().map(shift -> {
            List<Staffed> staffed = staffing.getOrDefault(shift.shiftId(), List.of());
            List<RoleAnswer> roles = shift.requiredJobRoles().stream()
                    .map(role -> roleAnswer(role.jobRoleId(), role.requiredEmployeeCount(), staffed))
                    .toList();
            return answer(shift, understaffed(roles), roles, staffed.stream().map(Staffed::assignment).toList());
        }).toList();
    }

    // ---- variant 4: the normalised write model -----------------------------------------------------

    private static final String SHIFTS_WITH_ROLES = """
            SELECT s.shift_id, s.shift_name, s.department_id, s.work_location_id, s.start_datetime,
                   s.end_datetime, s.shift_status, r.job_role_id, r.required_employee_count
            FROM shift s
            LEFT JOIN shift_required_job_role r ON r.shift_id = s.shift_id
            WHERE s.deleted = FALSE AND s.start_datetime < ? AND s.end_datetime > ? AND s.department_id = ?
            ORDER BY s.start_datetime, s.shift_id, r.shift_required_job_role_id
            """;

    private static final String ASSIGNMENTS_WITH_EMPLOYEES = """
            SELECT a.shift_id, a.shift_assignment_id, a.assignment_status, a.assigned_datetime,
                   e.employee_id, e.first_name, e.last_name, e.job_role_ids
            FROM shift_assignment a
            JOIN shift s ON s.shift_id = a.shift_id
            JOIN employee_replica e ON e.employee_id = a.employee_id AND e.deleted_at IS NULL
            WHERE a.deleted = FALSE
              AND s.deleted = FALSE AND s.start_datetime < ? AND s.end_datetime > ? AND s.department_id = ?
            ORDER BY a.shift_id, a.shift_assignment_id
            """;

    private record ShiftRow(long shiftId, String shiftName, long departmentId, long workLocationId,
                            Instant start, Instant end, String status, List<RequiredJobRoleSnapshot> roles) {
    }

    /** An assignment with what the staffing count needs: is it active, and which roles does the employee hold. */
    private record Staffed(AssignmentAnswer assignment, boolean active, List<Long> jobRoleIds) {
    }

    private List<ShiftAnswer> fromTheWriteModelWithJoins() {
        OffsetDateTime from = FROM.atOffset(ZoneOffset.UTC);
        OffsetDateTime to = TO.atOffset(ZoneOffset.UTC);

        Map<Long, ShiftRow> shifts = new LinkedHashMap<>();
        jdbc.query(SHIFTS_WITH_ROLES, rs -> {
            ShiftRow shift = shifts.get(rs.getLong("shift_id"));
            if (shift == null) {
                shift = new ShiftRow(rs.getLong("shift_id"), rs.getString("shift_name"),
                        rs.getLong("department_id"), rs.getLong("work_location_id"),
                        rs.getObject("start_datetime", OffsetDateTime.class).toInstant(),
                        rs.getObject("end_datetime", OffsetDateTime.class).toInstant(),
                        rs.getString("shift_status"), new ArrayList<>());
                shifts.put(shift.shiftId(), shift);
            }
            long jobRoleId = rs.getLong("job_role_id");
            if (!rs.wasNull()) {
                shift.roles().add(new RequiredJobRoleSnapshot(jobRoleId, rs.getInt("required_employee_count")));
            }
        }, to, from, DEPARTMENT_ID);

        Map<Long, List<Staffed>> staffing = new LinkedHashMap<>();
        jdbc.query(ASSIGNMENTS_WITH_EMPLOYEES, rs -> {
            String status = rs.getString("assignment_status");
            staffing.computeIfAbsent(rs.getLong("shift_id"), id -> new ArrayList<>()).add(new Staffed(
                    new AssignmentAnswer(rs.getLong("shift_assignment_id"), status,
                            rs.getObject("assigned_datetime", OffsetDateTime.class).toInstant(),
                            rs.getLong("employee_id"), rs.getString("first_name"), rs.getString("last_name")),
                    AssignmentStatus.valueOf(status).isActive(),
                    JOB_ROLE_IDS.convertToEntityAttribute(rs.getString("job_role_ids"))));
        }, to, from, DEPARTMENT_ID);

        return shifts.values().stream().map(shift -> {
            List<Staffed> staffed = staffing.getOrDefault(shift.shiftId(), List.of());
            List<RoleAnswer> roles = shift.roles().stream()
                    .map(role -> roleAnswer(role.jobRoleId(), role.requiredEmployeeCount(), staffed))
                    .toList();
            return new ShiftAnswer(shift.shiftId(), shift.shiftName(), shift.departmentId(),
                    shift.workLocationId(), shift.start(), shift.end(), shift.status(), understaffed(roles),
                    roles, staffed.stream().map(Staffed::assignment).toList());
        }).toList();
    }

    // ---- variant 5: the deployed endpoint ----------------------------------------------------------

    /** POST /graphql as a client would, with the response parsed back into the same answer. */
    private List<ShiftAnswer> overHttp() {
        ResponseEntity<String> response = http.postForEntity("/graphql",
                new HttpEntity<>(Map.of("query", WEEK_QUERY), authorized(managerToken)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body;
        try {
            body = objectMapper.readTree(response.getBody());
        } catch (IOException notJson) {
            throw new IllegalStateException("the GraphQL response is not JSON: " + response.getBody(), notJson);
        }
        assertThat(body.path("errors")).as("GraphQL errors: %s", body).isEmpty();
        List<ShiftAnswer> shifts = new ArrayList<>();
        for (JsonNode shift : body.path("data").path("shifts")) {
            List<RoleAnswer> roles = new ArrayList<>();
            for (JsonNode role : shift.path("requiredJobRoles")) {
                roles.add(new RoleAnswer(role.path("jobRoleId").asLong(), role.path("requiredEmployeeCount").asInt(),
                        role.path("assignedCount").asInt()));
            }
            List<AssignmentAnswer> assignments = new ArrayList<>();
            for (JsonNode assignment : shift.path("assignments")) {
                JsonNode employee = assignment.path("employee");
                assignments.add(new AssignmentAnswer(assignment.path("shiftAssignmentId").asLong(),
                        assignment.path("status").asText(), Instant.parse(assignment.path("assignedDatetime").asText()),
                        employee.path("employeeId").asLong(), employee.path("firstName").asText(),
                        employee.path("lastName").asText()));
            }
            shifts.add(new ShiftAnswer(shift.path("shiftId").asLong(), shift.path("shiftName").asText(),
                    shift.path("departmentId").asLong(), shift.path("workLocationId").asLong(),
                    Instant.parse(shift.path("startDatetime").asText()),
                    Instant.parse(shift.path("endDatetime").asText()), shift.path("status").asText(),
                    shift.path("isUnderstaffed").asBoolean(), roles, assignments));
        }
        return shifts;
    }

    // ---- shared shaping ----------------------------------------------------------------------------

    private static ShiftAnswer answer(ShiftView shift, boolean understaffed, List<RoleAnswer> roles,
                                      List<AssignmentAnswer> assignments) {
        return new ShiftAnswer(shift.shiftId(), shift.shiftName(), shift.departmentId(), shift.workLocationId(),
                shift.startDatetime(), shift.endDatetime(), shift.status().name(), understaffed, roles,
                assignments);
    }

    /** The staffing rule of SchedulingQueryService.requiredJobRoles: active assignments that hold the role. */
    private static RoleAnswer roleAnswer(long jobRoleId, int required, List<Staffed> staffed) {
        int assigned = (int) staffed.stream()
                .filter(entry -> entry.active() && entry.jobRoleIds().contains(jobRoleId))
                .count();
        return new RoleAnswer(jobRoleId, required, assigned);
    }

    private static boolean understaffed(List<RoleAnswer> roles) {
        return roles.stream()
                .anyMatch(role -> UnderstaffingRules.missing(role.requiredEmployeeCount(), role.assignedCount()) > 0);
    }

    // ---- data --------------------------------------------------------------------------------------

    private static List<Long> jobRolesOf(long employeeId) {
        long first = 1 + employeeId % 4;
        return employeeId % 3 == 0 ? List.of(first, 5L) : List.of(first);
    }

    private void loadEmployees() {
        List<Object[]> rows = new ArrayList<>();
        for (long id = 1; id <= (long) DEPARTMENTS * EMPLOYEES_PER_DEPARTMENT; id++) {
            String first = "First" + id;
            String last = "Last" + id;
            readModelWrites.upsertEmployee(new EmployeeView(id, first, last, jobRolesOf(id), "ACTIVE", 37, 1, null));
            rows.add(new Object[]{id, first, last, "ACTIVE", JOB_ROLE_IDS.convertToDatabaseColumn(jobRolesOf(id)),
                    1L, 37.0});
        }
        jdbc.batchUpdate("""
                INSERT INTO employee_replica (employee_id, first_name, last_name, employment_status, job_role_ids,
                                              aggregate_version, snapshot_version, weekly_contract_hours)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?)
                """, rows);
    }

    /**
     * Loads the days [fromDay, toDay) into both stores from one generator. The read model is written
     * through its own port, so the documents have exactly the shape the projection gives them. Some
     * shifts and assignments are cancelled or deleted, so the filters of both sides are exercised.
     */
    private void loadDays(int fromDay, int toDay) {
        int perDay = DEPARTMENTS * SHIFTS_PER_DAY;
        List<ShiftView> shifts = new ArrayList<>();
        List<AssignmentView> assignments = new ArrayList<>();
        for (int day = fromDay; day < toDay; day++) {
            for (int department = 0; department < DEPARTMENTS; department++) {
                for (int slot = 0; slot < SHIFTS_PER_DAY; slot++) {
                    long shiftId = (long) day * perDay + department * SHIFTS_PER_DAY + slot + 1;
                    Instant start = EPOCH.plus(Duration.ofDays(day)).plus(Duration.ofHours(6 + 8L * slot));
                    Instant end = start.plus(Duration.ofHours(8));
                    ShiftStatus status = shiftId % 15 == 0 ? ShiftStatus.CANCELLED : ShiftStatus.PUBLISHED;
                    Instant shiftDeletedAt = shiftId % 40 == 13 ? start : null;
                    shifts.add(new ShiftView(shiftId, department + 1, 1 + department % 3,
                            "Dept " + (department + 1) + " day " + day + " slot " + slot, start, end, status,
                            List.of(new RequiredJobRoleSnapshot(1, 2), new RequiredJobRoleSnapshot(2, 2),
                                    new RequiredJobRoleSnapshot(3, 1)),
                            1, shiftDeletedAt));
                    for (int seat = 0; seat < ASSIGNMENTS_PER_SHIFT; seat++) {
                        long assignmentId = (shiftId - 1) * ASSIGNMENTS_PER_SHIFT + seat + 1;
                        long employeeId = (long) department * EMPLOYEES_PER_DEPARTMENT
                                + (shiftId * ASSIGNMENTS_PER_SHIFT + seat) % EMPLOYEES_PER_DEPARTMENT + 1;
                        AssignmentStatus assignmentStatus = switch ((int) (assignmentId % 10)) {
                            case 0 -> AssignmentStatus.CANCELLED;
                            case 1 -> AssignmentStatus.APPROVED;
                            default -> AssignmentStatus.ASSIGNED;
                        };
                        Instant assignmentDeletedAt = assignmentId % 25 == 7 ? start : null;
                        assignments.add(new AssignmentView(assignmentId, shiftId, employeeId, assignmentStatus,
                                start.minus(Duration.ofDays(7)), null, null, start, end, 1, assignmentDeletedAt));
                    }
                }
            }
        }

        // Distinct ids, so the version-guarded upserts of the port cannot race each other.
        shifts.parallelStream().forEach(readModelWrites::upsertShift);
        assignments.parallelStream().forEach(readModelWrites::upsertAssignment);

        jdbc.batchUpdate("""
                INSERT INTO shift (shift_id, department_id, work_location_id, shift_name, start_datetime,
                                   end_datetime, shift_status, deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, shifts.stream().map(shift -> new Object[]{shift.shiftId(), shift.departmentId(),
                shift.workLocationId(), shift.shiftName(), shift.startDatetime().atOffset(ZoneOffset.UTC),
                shift.endDatetime().atOffset(ZoneOffset.UTC), shift.status().name(), shift.deleted()}).toList());
        jdbc.batchUpdate("""
                INSERT INTO shift_required_job_role (shift_id, job_role_id, required_employee_count)
                VALUES (?, ?, ?)
                """, shifts.stream().flatMap(shift -> shift.requiredJobRoles().stream()
                .map(role -> new Object[]{shift.shiftId(), role.jobRoleId(), role.requiredEmployeeCount()}))
                .toList());
        jdbc.batchUpdate("""
                INSERT INTO shift_assignment (shift_assignment_id, shift_id, employee_id, assignment_status,
                                              assigned_datetime, deleted)
                VALUES (?, ?, ?, ?, ?, ?)
                """, assignments.stream().map(assignment -> new Object[]{assignment.shiftAssignmentId(),
                assignment.shiftId(), assignment.employeeId(), assignment.status().name(),
                assignment.assignedDatetime().atOffset(ZoneOffset.UTC), assignment.deleted()}).toList());
    }

    // ---- reporting ---------------------------------------------------------------------------------

    private static long rowsIn(String table) {
        Long rows = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return rows == null ? 0 : rows;
    }

    /** Nearest-rank percentile of an ascending array. */
    private static long percentile(long[] sorted, int percent) {
        int rank = (int) Math.ceil(percent / 100.0 * sorted.length);
        return sorted[Math.max(0, rank - 1)];
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2f", nanos / 1_000_000.0);
    }

    private String environment() {
        Runtime runtime = Runtime.getRuntime();
        String mongoVersion = mongo.executeCommand(new Document("buildInfo", 1)).getString("version");
        String postgresVersion = jdbc.queryForObject("SHOW server_version", String.class);
        return String.format(Locale.ROOT, "%d CPUs, %d MB max heap, Java %s, %s %s, MongoDB %s, PostgreSQL %s",
                runtime.availableProcessors(), runtime.maxMemory() / (1024 * 1024),
                System.getProperty("java.version"), System.getProperty("os.name"), System.getProperty("os.arch"),
                mongoVersion, postgresVersion);
    }

    /**
     * Three copies of the same lines: the build log (grep for the marker), a file next to the Failsafe
     * reports (uploaded by CI as the reports-scheduling-service artifact) and, on GitHub Actions, the
     * job summary.
     */
    private static void publishReport(List<String> report) throws IOException {
        report.forEach(line -> System.out.println(MARKER + line));
        Path file = Path.of("target", "failsafe-reports", "cqrs-read-model-benchmark.md");
        Files.createDirectories(file.getParent());
        Files.write(file, report);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null && !summary.isBlank()) {
            List<String> lines = new ArrayList<>(List.of("### CQRS read model benchmark (P3.6)", ""));
            lines.addAll(report);
            lines.add("");
            Files.write(Path.of(summary), lines, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }
}
