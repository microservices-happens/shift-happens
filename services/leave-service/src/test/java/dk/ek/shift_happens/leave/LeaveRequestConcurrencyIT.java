package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.leave.application.LeaveApprovalService;
import dk.ek.shift_happens.leave.application.LeaveRequestService;
import dk.ek.shift_happens.leave.domain.ApprovalDecision;
import dk.ek.shift_happens.leave.domain.LeaveRequestRejectedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Concurrent changes to the same leave request or the same employee's calendar. Deterministic: the first
 * transaction is held open on a latch and the test waits (Awaitility) until the second one is really blocked
 * on a lock in PostgreSQL, so no sleep is involved.
 */
class LeaveRequestConcurrencyIT extends AbstractCommandIT {

    private static final String CANCELLED = "leave.request.cancelled.v1";
    private static final String SUBMITTED = "leave.request.submitted.v1";
    private static final String REJECTED = "leave.request.rejected.v1";

    @Autowired
    private LeaveRequestService requests;
    @Autowired
    private LeaveApprovalService approvals;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aRejectRacingAnUncommittedCancelCannotOverwriteIt() throws Exception {
        long typeId = createLeaveType("Race " + UUID.randomUUID());
        seedEmployee(1001);
        seedEmployee(1990);
        long id = post("/v1/leaverequests", Map.of("employeeId", 1001, "leaveTypeId", typeId,
                "startDate", "2029-01-10", "endDate", "2029-01-12"), JsonNode.class)
                .getBody().path("leaveRequestId").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch cancelled = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<?> a = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                requests.cancel(id, null);
                cancelled.countDown();
                awaitLatch(commit);
            }));
            assertThat(cancelled.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> b = pool.submit(() -> approvals.decide(id, 1990, ApprovalDecision.REJECTED, "no", null));
            await().atMost(Duration.ofSeconds(15)).until(() -> b.isDone() || sessionsBlockedOnALock() > 0);
            commit.countDown();
            a.get(15, TimeUnit.SECONDS);

            assertThatThrownBy(() -> b.get(15, TimeUnit.SECONDS))
                    .as("the reject must see the committed CANCELLED and be refused")
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(LeaveRequestRejectedException.class);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT request_status FROM leave_request WHERE leave_request_id = ?",
                String.class, id)).isEqualTo("CANCELLED");
        List<JsonNode> cancelledEvents = outboxEvents(CANCELLED, id);
        assertThat(cancelledEvents).hasSize(1);
        assertThat(cancelledEvents.get(0).path("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(outboxEvents(REJECTED, id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? "
                        + "AND event_type IN (?, ?) AND payload::jsonb ->> 'aggregateVersion' = '2'",
                Integer.class, id, CANCELLED, REJECTED)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_approval WHERE leave_request_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void aCancelRacingAnUncommittedEditSeesTheEditAndAnnouncesIt() throws Exception {
        long typeId = createLeaveType("EditCancel " + UUID.randomUUID());
        seedEmployee(1002);
        long id = post("/v1/leaverequests", Map.of("employeeId", 1002, "leaveTypeId", typeId,
                "startDate", "2029-02-10", "endDate", "2029-02-12"), JsonNode.class)
                .getBody().path("leaveRequestId").asLong();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch edited = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<?> a = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                requests.edit(id, null, null, LocalDate.of(2029, 2, 14), null, null);
                edited.countDown();
                awaitLatch(commit);
            }));
            assertThat(edited.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> b = pool.submit(() -> requests.cancel(id, null));
            await().atMost(Duration.ofSeconds(15)).until(() -> b.isDone() || sessionsBlockedOnALock() > 0);
            commit.countDown();
            a.get(15, TimeUnit.SECONDS);
            b.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<JsonNode> submitted = outboxEvents(SUBMITTED, id);
        assertThat(submitted).extracting(e -> e.path("aggregateVersion").asLong()).containsExactly(1L, 2L);
        List<JsonNode> cancelledEvents = outboxEvents(CANCELLED, id);
        assertThat(cancelledEvents).hasSize(1);
        assertThat(cancelledEvents.get(0).path("aggregateVersion").asLong()).isEqualTo(3);
        assertThat(cancelledEvents.get(0).path("data").path("endDate").asText())
                .as("the cancelled snapshot carries the edited end date").isEqualTo("2029-02-14");
        assertThat(jdbc.queryForMap("SELECT request_status, aggregate_version FROM leave_request "
                + "WHERE leave_request_id = ?", id))
                .containsEntry("request_status", "CANCELLED").containsEntry("aggregate_version", 3L);
    }

    @Test
    void parallelOverlappingSubmissionsForOneEmployeeYieldExactlyOneAcceptedAndNoServerError() throws Exception {
        long typeId = createLeaveType("Storm " + UUID.randomUUID());
        int rounds = 10;
        int parallel = 6;
        ExecutorService pool = Executors.newFixedThreadPool(parallel);
        try {
            for (int round = 0; round < rounds; round++) {
                long employeeId = 1100 + round;
                seedEmployee(employeeId);
                CyclicBarrier gate = new CyclicBarrier(parallel);
                List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
                for (int i = 0; i < parallel; i++) {
                    // Overlapping, not identical, ranges: all share 2029-03-05.
                    String start = "2029-03-0" + (1 + i % 5);
                    futures.add(pool.submit(() -> {
                        gate.await(15, TimeUnit.SECONDS);
                        return http.postForEntity("/v1/leaverequests", new HttpEntity<>(
                                Map.of("employeeId", employeeId, "leaveTypeId", typeId, "startDate", start,
                                        "endDate", "2029-03-05"),
                                headersFor(UUID.randomUUID().toString(), null)), String.class);
                    }));
                }

                int accepted = 0;
                for (Future<ResponseEntity<String>> future : futures) {
                    ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
                    int status = response.getStatusCode().value();
                    assertThat(status).as("round %d: %s", round, response.getBody())
                            .isIn(HttpStatus.ACCEPTED.value(), HttpStatus.CONFLICT.value());
                    if (status == HttpStatus.ACCEPTED.value()) {
                        accepted++;
                    } else {
                        assertThat(response.getHeaders().getContentType().toString())
                                .as("round %d", round).contains("problem+json");
                        JsonNode problem = objectMapper.readTree(response.getBody());
                        assertThat(problem.path("title").asText()).as("round %d: %s", round, response.getBody())
                                .isEqualTo("Leave request rejected");
                        assertThat(problem.path("detail").asText()).as("round %d", round)
                                .contains("already has leave request");
                    }
                }
                assertThat(accepted).as("round %d accepted", round).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_request WHERE employee_id = ? "
                        + "AND request_status = 'PENDING'", Integer.class, employeeId))
                        .as("round %d pending rows", round).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Sessions of this database that currently wait for a heavyweight lock, other than the test's own query. */
    private int sessionsBlockedOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'", Integer.class);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch not released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
