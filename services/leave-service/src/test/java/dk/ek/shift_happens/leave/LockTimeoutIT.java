package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lock wait is bounded by {@code lock_timeout} (DB_LOCK_TIMEOUT, default 5 s, set per connection), so a
 * request stuck behind a foreign lock is answered 409 problem+json instead of hanging and starving the pool.
 */
class LockTimeoutIT extends AbstractCommandIT {

    @Test
    void aSubmitBlockedOnTheEmployeeCalendarLockIsAnswered409() throws Exception {
        long typeId = createLeaveType("Lock " + UUID.randomUUID());
        long employeeId = 1301;
        seedEmployee(employeeId);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = holderConnection()) {
            try (PreparedStatement lock = holder.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                lock.setLong(1, employeeId);
                lock.execute();
            }
            Future<ResponseEntity<JsonNode>> response = pool.submit(() -> post("/v1/leaverequests",
                    Map.of("employeeId", employeeId, "leaveTypeId", typeId,
                            "startDate", "2029-05-01", "endDate", "2029-05-02"), JsonNode.class));

            assertConflictingChange(response.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_request WHERE employee_id = ?",
                Integer.class, employeeId)).isZero();
    }

    @Test
    void anEditBlockedOnTheRequestRowLockIsAnswered409() throws Exception {
        long typeId = createLeaveType("Lock " + UUID.randomUUID());
        long employeeId = 1302;
        seedEmployee(employeeId);
        long id = post("/v1/leaverequests", Map.of("employeeId", employeeId, "leaveTypeId", typeId,
                "startDate", "2029-06-01", "endDate", "2029-06-02"), JsonNode.class)
                .getBody().path("leaveRequestId").asLong();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = holderConnection()) {
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT leave_request_id FROM leave_request WHERE leave_request_id = ? FOR UPDATE")) {
                lock.setLong(1, id);
                lock.execute();
            }
            Future<ResponseEntity<JsonNode>> response = pool.submit(() -> http.exchange("/v1/leaverequests/" + id,
                    HttpMethod.PATCH, new HttpEntity<>(Map.of("reason", "changed"), headersFor(null, null)),
                    JsonNode.class));

            assertConflictingChange(response.get(15, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM leave_request WHERE leave_request_id = ?",
                Long.class, id)).isEqualTo(1L);
    }

    private static void assertConflictingChange(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(409);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(response.getBody().path("title").asText()).isEqualTo("Conflicting change");
    }

    /** A raw connection with an open transaction; closing it rolls back and releases its locks. */
    private static Connection holderConnection() throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        connection.setAutoCommit(false);
        return connection;
    }
}
