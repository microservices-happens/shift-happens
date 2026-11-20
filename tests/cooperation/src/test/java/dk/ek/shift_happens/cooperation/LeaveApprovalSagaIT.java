package dk.ek.shift_happens.cooperation;

import dk.ek.shift_happens.cooperation.support.ServiceContainer;
import dk.ek.shift_happens.cooperation.support.TestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The required system-level cooperation test (P3.1 / P6.5): leave-command orchestrates the leave-approval saga and
 * scheduling-command releases or refuses the conflicting assignments. Real leave-service and scheduling-service images,
 * their PostgreSQL databases and RabbitMQ, all in containers on one network; the assertions read each service's own
 * database, so nothing is mocked and no non-contract endpoint is used.
 *
 * <ul>
 *   <li>completed path: the manager approves, scheduling releases the employee's overlapping assignment and answers
 *       {@code scheduling.leave-release.completed.v1}, leave publishes {@code leave.request.approved.v1} and the
 *       request is APPROVED;</li>
 *   <li>compensation path: the employee's shift starts within 24 hours (LOCKED), so scheduling changes nothing and
 *       answers {@code scheduling.leave-release.rejected.v1}; leave appends a REVERSAL ledger entry, the request is
 *       REJECTED with scheduling's reason and {@code leave.request.rejected.v1} is published.</li>
 * </ul>
 *
 * The employees reach both replicas the way they do in production: as {@code workforce.employee.created.v1} events
 * on the exchange (published through the RabbitMQ management API, so the test needs no AMQP client).
 */
class LeaveApprovalSagaIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final DockerImageName POSTGRES = DockerImageName.parse("postgres:17-alpine");
    private static final String EXCHANGE = "shift-happens.events";
    private static final long MANAGER = 1;
    private static final long JOB_ROLE = 7;

    private static RabbitMQContainer rabbit;
    private static PostgreSQLContainer<?> leaveDb;
    private static PostgreSQLContainer<?> schedulingDb;
    private static GenericContainer<?> leave;
    private static GenericContainer<?> scheduling;
    private static GenericContainer<?> jwks;

    @BeforeAll
    static void startStack() {
        ServiceContainer.requireDockerOrSkip();

        rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
                .withNetwork(NETWORK)
                .withNetworkAliases("rabbitmq")
                .withAdminUser("shift")
                .withAdminPassword("shift_dev");
        rabbit.start();

        leaveDb = new PostgreSQLContainer<>(POSTGRES)
                .withNetwork(NETWORK).withNetworkAliases("leave-db")
                .withDatabaseName("leave").withUsername("leave").withPassword("leave_dev");
        schedulingDb = new PostgreSQLContainer<>(POSTGRES)
                .withNetwork(NETWORK).withNetworkAliases("scheduling-db")
                .withDatabaseName("scheduling").withUsername("scheduling").withPassword("scheduling_dev");
        leaveDb.start();
        schedulingDb.start();

        // Scheduling validates the JWT itself (P1.7). Identity is not part of this test, so a static JWKS
        // stands in for it and the test signs its own Manager token.
        jwks = TestTokens.jwksServer(NETWORK);
        jwks.start();

        // The services declare the exchange and their queues on start, so both must be up before anything is published.
        scheduling = ServiceContainer.create("scheduling-service", NETWORK)
                .withEnv(serviceEnvironment("scheduling-db", "scheduling", "scheduling", "scheduling_dev"))
                .withEnv("JWT_ISSUER", TestTokens.ISSUER)
                .withEnv("JWT_JWKS_URL", TestTokens.JWKS_URL);
        leave = ServiceContainer.create("leave-service", NETWORK)
                .withEnv(serviceEnvironment("leave-db", "leave", "leave", "leave_dev"));
        scheduling.start();
        leave.start();
    }

    @AfterAll
    static void stopStack() {
        close(leave);
        close(scheduling);
        close(jwks);
        close(leaveDb);
        close(schedulingDb);
        close(rabbit);
        NETWORK.close();
    }

    @Test
    void approvedLeaveReleasesOverlappingShiftsAndCompensatesWhenAShiftIsLocked() throws Exception {
        publishEmployee(MANAGER, "Manager");
        long releasedEmployee = 101;
        long lockedEmployee = 102;
        publishEmployee(releasedEmployee, "Employee");
        publishEmployee(lockedEmployee, "Employee");
        for (long employeeId : new long[] {MANAGER, releasedEmployee, lockedEmployee}) {
            awaitReplica(leaveDb, employeeId);
            awaitReplica(schedulingDb, employeeId);
        }
        long leaveTypeId = idOf(post(leave, "/v1/leavetypes", "{\"leaveTypeName\":\"Vacation\"}"), "leaveTypeId");
        for (long employeeId : new long[] {releasedEmployee, lockedEmployee}) {
            assertThat(post(leave, "/v1/leaveledgers", """
                    {"employeeId":%d,"leaveTypeId":%d,"changeAmountDays":10,"transactionType":"ACCRUAL"}
                    """.formatted(employeeId, leaveTypeId)).statusCode()).isEqualTo(202);
        }

        // ---- completed path: the shift starts in 5 days, so it is not locked ----
        Instant farStart = Instant.now().plusSeconds(5 * 24 * 3600);
        long farAssignment = assignedShift(releasedEmployee, farStart);
        long released = submitAndApprove(releasedEmployee, leaveTypeId, farStart);

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(text(leaveDb,
                "SELECT request_status FROM leave_request WHERE leave_request_id = " + released))
                .isEqualTo("APPROVED"));
        assertThat(text(schedulingDb, "SELECT assignment_status FROM shift_assignment "
                + "WHERE shift_assignment_id = " + farAssignment)).isEqualTo("RELEASED_FOR_LEAVE");
        assertThat(text(leaveDb, "SELECT saga_status FROM leave_approval_saga WHERE leave_request_id = "
                + released)).isEqualTo("COMPLETED");
        awaitPublished(released, "leave.request.approved.v1");
        assertThat(text(schedulingDb, "SELECT result FROM leave_release WHERE leave_request_id = " + released))
                .isEqualToIgnoringCase("COMPLETED");
        assertThat(count(leaveDb, "SELECT count(*) FROM leave_ledger WHERE employee_id = " + releasedEmployee
                + " AND transaction_type = 'RESERVATION'")).isEqualTo(1);
        assertThat(count(leaveDb, "SELECT count(*) FROM leave_ledger WHERE employee_id = " + releasedEmployee
                + " AND transaction_type = 'REVERSAL'")).isZero();

        // ---- compensation path: the shift starts in 2 hours, so it is LOCKED ----
        Instant nearStart = Instant.now().plusSeconds(2 * 3600);
        long lockedAssignment = assignedShift(lockedEmployee, nearStart);
        long refused = submitAndApprove(lockedEmployee, leaveTypeId, nearStart);

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> assertThat(text(leaveDb,
                "SELECT request_status FROM leave_request WHERE leave_request_id = " + refused))
                .isEqualTo("REJECTED"));
        assertThat(text(leaveDb, "SELECT rejection_reason FROM leave_request WHERE leave_request_id = " + refused))
                .contains("starts within 24 hours");
        assertThat(text(leaveDb, "SELECT saga_status FROM leave_approval_saga WHERE leave_request_id = " + refused))
                .isEqualTo("COMPENSATED");
        assertThat(text(leaveDb, "SELECT string_agg(transaction_type || ':' || change_amount_days, ',' "
                + "ORDER BY entry_number) FROM leave_ledger WHERE employee_id = " + lockedEmployee))
                .isEqualTo("ACCRUAL:10.00,RESERVATION:-1.00,REVERSAL:1.00");
        awaitPublished(refused, "leave.request.rejected.v1");
        assertThat(count(leaveDb, "SELECT count(*) FROM outbox_event WHERE event_type = "
                + "'leave.request.approved.v1' AND aggregate_id = " + refused)).isZero();
        assertThat(text(schedulingDb, "SELECT assignment_status FROM shift_assignment "
                + "WHERE shift_assignment_id = " + lockedAssignment)).as("nothing changed in Scheduling")
                .isEqualTo("ASSIGNED");
        assertThat(text(schedulingDb, "SELECT result FROM leave_release WHERE leave_request_id = " + refused))
                .isEqualToIgnoringCase("REJECTED");
    }

    /** A shift for the employee at {@code start}, 8 hours long, and the assignment of the employee to it. */
    private static long assignedShift(long employeeId, Instant start) throws Exception {
        HttpResponse<String> shift = post(scheduling, "/v1/shifts", """
                {"departmentId":2,"workLocationId":3,"shiftName":"Shift %d","startDatetime":"%s",
                 "endDatetime":"%s","shiftStatus":"PLANNED",
                 "requiredJobRoles":[{"jobRoleId":%d,"requiredEmployeeCount":1}]}
                """.formatted(employeeId, start, start.plusSeconds(8 * 3600), JOB_ROLE));
        assertThat(shift.statusCode()).as(shift.body()).isEqualTo(202);
        HttpResponse<String> assignment = post(scheduling, "/v1/shiftassignments", """
                {"shiftId":%d,"employeeId":%d,"assignmentStatus":"ASSIGNED"}
                """.formatted(idOf(shift, "shiftId"), employeeId));
        assertThat(assignment.statusCode()).as(assignment.body()).isEqualTo(202);
        return idOf(assignment, "shiftAssignmentId");
    }

    /** The employee asks for the day of the shift off and the manager approves it; returns the request id. */
    private static long submitAndApprove(long employeeId, long leaveTypeId, Instant shiftStart) throws Exception {
        LocalDate day = LocalDate.ofInstant(shiftStart, ZoneOffset.UTC);
        HttpResponse<String> request = post(leave, "/v1/leaverequests", """
                {"employeeId":%d,"leaveTypeId":%d,"startDate":"%s","endDate":"%s"}
                """.formatted(employeeId, leaveTypeId, day, day));
        assertThat(request.statusCode()).as(request.body()).isEqualTo(202);
        long leaveRequestId = idOf(request, "leaveRequestId");

        HttpResponse<String> approval = post(leave, "/v1/leaveapprovals", """
                {"leaveRequestId":%d,"approverEmployeeId":%d,"decision":"APPROVED","leaveComment":"enjoy"}
                """.formatted(leaveRequestId, MANAGER));
        assertThat(approval.statusCode()).as(approval.body()).isEqualTo(202);
        return leaveRequestId;
    }

    // ---- plumbing ----

    /** The relay has sent exactly one event of this type about the request (it polls, so allow it a moment). */
    private static void awaitPublished(long leaveRequestId, String eventType) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(count(leaveDb,
                "SELECT count(*) FROM outbox_event WHERE event_type = '" + eventType + "' AND aggregate_id = "
                        + leaveRequestId + " AND published_at IS NOT NULL")).isEqualTo(1));
    }

    private static Map<String, String> serviceEnvironment(String dbHost, String dbName, String dbUser,
                                                          String dbPassword) {
        Map<String, String> env = new HashMap<>();
        env.put("DB_HOST", dbHost);
        env.put("DB_PORT", "5432");
        env.put("DB_NAME", dbName);
        env.put("DB_USER", dbUser);
        env.put("DB_PASSWORD", dbPassword);
        env.put("RABBITMQ_HOST", "rabbitmq");
        env.put("RABBITMQ_PORT", "5672");
        env.put("RABBITMQ_USER", "shift");
        env.put("RABBITMQ_PASSWORD", "shift_dev");
        env.put("RABBITMQ_VHOST", "/");
        env.put("OUTBOX_RELAY_DELAY", "500");
        return env;
    }

    /** workforce.employee.created.v1 as workforce-service publishes it (contracts/events/payloads/employee.schema.json). */
    private static void publishEmployee(long employeeId, String userRole) throws Exception {
        String payload = """
                {"eventId":"%s","eventType":"workforce.employee.created.v1","occurredAt":"%s",
                 "producer":"workforce-service","aggregateId":%d,"aggregateVersion":1,
                 "correlationId":"%s","data":{"employeeId":%d,"employeeNumber":"EMP-%d","userRole":"%s",
                 "firstName":"Ada","lastName":"Lovelace","email":"ada%d@shift.dk","employmentStatus":"ACTIVE",
                 "jobRoleIds":[%d],"weeklyContractHours":37.5}}
                """.formatted(UUID.randomUUID(), Instant.now(), employeeId, UUID.randomUUID(), employeeId, employeeId,
                userRole, employeeId, JOB_ROLE);
        String body = "{\"properties\":{\"content_type\":\"application/json\"},\"routing_key\":\"workforce.employee."
                + "created.v1\",\"payload\":" + jsonString(payload) + ",\"payload_encoding\":\"string\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(rabbit.getHttpUrl() + "/api/exchanges/%2F/" + EXCHANGE
                        + "/publish"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("shift:shift_dev".getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        // HTTP/1.1: the management listener does not take the h2c upgrade the default client attempts
        HttpResponse<String> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).as("the exchange routed the event to at least one queue").contains("true");
    }

    private static String jsonString(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static void awaitReplica(PostgreSQLContainer<?> database, long employeeId) {
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(count(database,
                "SELECT count(*) FROM employee_replica WHERE employee_id = " + employeeId)).isEqualTo(1));
    }

    private static HttpResponse<String> post(GenericContainer<?> service, String path, String body) throws Exception {
        URI uri = URI.create("http://" + service.getHost() + ":"
                + service.getMappedPort(ServiceContainer.HTTP_PORT) + path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                // The manager is the caller of every write in this scenario (contracts/README.md, role rules).
                .header("Authorization", "Bearer " + TestTokens.token(MANAGER, "Manager"))
                // Every POST requires a per-action key (contracts/README.md, Idempotency).
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static long idOf(HttpResponse<String> response, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(\\d+)").matcher(response.body());
        assertThat(matcher.find()).as("%s in %s", field, response.body()).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static long count(PostgreSQLContainer<?> database, String sql) {
        try (Connection connection = DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }

    private static String text(PostgreSQLContainer<?> database, String sql) {
        try (Connection connection = DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("Query failed: " + sql, e);
        }
    }

    private static void close(AutoCloseable container) {
        if (container == null) {
            return;
        }
        try {
            container.close();
        } catch (Exception ignored) {
            // best-effort cleanup
        }
    }
}
