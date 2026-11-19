package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.leave.application.LeaveApprovalSagaService;
import dk.ek.shift_happens.leave.domain.LeaveRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * A reply of Scheduling and the timeout job (or a cancel) racing on the same saga: the request row lock and the
 * status re-check make exactly one of them decide. The lock cases are deterministic (a transaction holds the row on a
 * latch and the test waits, with Awaitility, until the other side is really blocked in PostgreSQL); the last test
 * lets the two run truly in parallel.
 */
class LeaveApprovalSagaConcurrencyIT extends AbstractSagaIT {

    @Autowired
    private LeaveApprovalSagaService sagaService;
    @Autowired
    private LeaveRequestRepository requestRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aReplyBeingAppliedMakesTheTimeoutSkipTheSagaAndTheReplyWins() throws Exception {
        long id = scenario(7101, "10");
        approve(id, null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            // stands for the reply transaction: it owns the request row while the job runs
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                requestRepository.findByIdForUpdate(id).orElseThrow();
                held.countDown();
                awaitLatch(commit);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            makeOverdue(id); // only now: the row is locked, so the scheduled job cannot get in before the reply
            UUID reply = reply(COMPLETED_REPLY, id, startedEventId(id, 1), null);
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsBlockedOnALock() > 0);

            long sagaId = (Long) saga(id, 1).get("leave_approval_saga_id");
            long t0 = System.nanoTime();
            boolean timedOut = sagaService.timeOut(sagaId, id); // must not throw: SKIP LOCKED, no lock_timeout wait
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            assertThat(timedOut).as("the locked row is skipped").isFalse();
            assertThat(elapsedMs).as("SKIP LOCKED does not wait for lock_timeout").isLessThan(1000);
            assertThat(saga(id, 1)).containsEntry("saga_status", "STARTED");
            commit.countDown();
            holder.get(15, TimeUnit.SECONDS);
            awaitStatus(id, "APPROVED");
            awaitProcessed(reply);
        } finally {
            pool.shutdownNow();
        }

        timeoutJob.runOnce();
        assertThat(saga(id, 1)).containsEntry("saga_status", "COMPLETED");
        assertThat(ledgerRows(7101)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1");
        assertThat(outboxEvents(APPROVED, id)).hasSize(1);
        assertThat(outboxEvents(REJECTED, id)).isEmpty();
    }

    @Test
    void aTimeoutBeingAppliedMakesALateRejectedReplyWaitAndBeIgnored() throws Exception {
        long id = scenario(7102, "10");
        approve(id, null);
        UUID start = startedEventId(id, 1);
        long sagaId = (Long) saga(id, 1).get("leave_approval_saga_id");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        UUID reply;
        try {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<Boolean> holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(s -> {
                // overdue only inside this transaction, so the scheduled job cannot time the saga out first
                jdbc.update("UPDATE leave_approval_saga SET deadline = now() - interval '1 minute' "
                        + "WHERE leave_approval_saga_id = ?", sagaId);
                boolean done = sagaService.timeOut(sagaId, id); // compensated, not yet committed
                held.countDown();
                awaitLatch(commit);
                return done;
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            reply = reply(REJECTED_REPLY, id, start, "locked at the same moment");
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsBlockedOnALock() > 0);
            commit.countDown();
            assertThat(holder.get(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        awaitProcessed(reply);

        assertThat(saga(id, 1)).containsEntry("saga_status", "TIMED_OUT");
        assertThat(requestStatus(id)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT rejection_reason FROM leave_request WHERE leave_request_id = ?",
                String.class, id)).isEqualTo(TIMEOUT_REASON);
        assertThat(ledgerRows(7102)).as("one reversal").containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1",
                "REVERSAL:3.00:1");
        List<JsonNode> rejected = outboxEvents(REJECTED, id);
        assertThat(rejected).as("one terminal event").hasSize(1);
        assertThat(rejected.get(0).path("data").path("rejectionReason").asText()).isEqualTo(TIMEOUT_REASON);
    }

    @Test
    void aTimeoutBeingAppliedMakesALateCompletedReplyWaitAndRetry() throws Exception {
        long id = scenario(7103, "10");
        approve(id, null);
        UUID start = startedEventId(id, 1);
        long sagaId = (Long) saga(id, 1).get("leave_approval_saga_id");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<Boolean> holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(s -> {
                jdbc.update("UPDATE leave_approval_saga SET deadline = now() - interval '1 minute' "
                        + "WHERE leave_approval_saga_id = ?", sagaId);
                boolean done = sagaService.timeOut(sagaId, id);
                held.countDown();
                awaitLatch(commit);
                return done;
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            reply(COMPLETED_REPLY, id, start, null);
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsBlockedOnALock() > 0);
            commit.countDown();
            assertThat(holder.get(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        awaitStatus(id, "APPROVAL_PENDING");

        assertThat(saga(id, 1)).containsEntry("saga_status", "TIMED_OUT");
        assertThat(saga(id, 2)).containsEntry("saga_status", "STARTED");
        assertThat(ledgerRows(7103)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1", "REVERSAL:3.00:1",
                "RESERVATION:-3.00:2");
        assertThat(outboxEvents(REJECTED, id)).hasSize(1);
        assertThat(outboxEvents(STARTED, id)).hasSize(2);
        assertThat(outboxEvents(APPROVED, id)).isEmpty();
    }

    @Test
    void aStaleTimeoutCandidateIsRecheckedUnderTheLockAndLeftAlone() {
        long id = scenario(7104, "10");
        approve(id, null);
        long sagaId = (Long) saga(id, 1).get("leave_approval_saga_id");
        UUID reply = reply(COMPLETED_REPLY, id, startedEventId(id, 1), null);
        awaitStatus(id, "APPROVED");
        awaitProcessed(reply);
        // the job read this saga as overdue before the reply committed; now it is COMPLETED
        jdbc.update("UPDATE leave_approval_saga SET deadline = now() - interval '1 minute' WHERE leave_request_id = ?",
                id);

        assertThat(sagaService.timeOut(sagaId, id)).isFalse();

        assertThat(saga(id, 1)).containsEntry("saga_status", "COMPLETED");
        assertThat(requestStatus(id)).isEqualTo("APPROVED");
        assertThat(ledgerRows(7104)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1");
        assertThat(outboxEvents(REJECTED, id)).isEmpty();
    }

    @Test
    void aSagaWhoseDeadlineHasNotPassedIsNotTimedOutEvenWhenAskedTo() {
        long id = scenario(7105, "10");
        approve(id, null);
        long sagaId = (Long) saga(id, 1).get("leave_approval_saga_id");

        assertThat(sagaService.timeOut(sagaId, id)).isFalse();

        assertThat(saga(id, 1)).containsEntry("saga_status", "STARTED");
        assertThat(ledgerRows(7105)).hasSize(2);
    }

    @Test
    void aCancelRacingTheReplyThatApprovesEndsConsistentWhicheverWins() throws Exception {
        long id = scenario(7106, "10");
        approve(id, null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                requestRepository.findByIdForUpdate(id).orElseThrow();
                held.countDown();
                awaitLatch(commit);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            UUID reply = reply(COMPLETED_REPLY, id, startedEventId(id, 1), null);
            Future<Integer> cancel = pool.submit(() -> delete("/v1/leaverequests/" + id).getStatusCode().value());
            await().atMost(Duration.ofSeconds(15)).until(() -> sessionsBlockedOnALock() > 1);
            commit.countDown();
            holder.get(15, TimeUnit.SECONDS);
            Integer status = cancel.get(30, TimeUnit.SECONDS);
            awaitProcessed(reply);

            // Whichever of the two got the lock first, the end state is consistent: either the cancel found
            // APPROVAL_PENDING (409) and the reply approved, or the reply approved and the cancel reversed it.
            if (status == 409) {
                assertThat(requestStatus(id)).isEqualTo("APPROVED");
                assertThat(ledgerRows(7106)).hasSize(2);
                assertThat(outboxEvents(CANCELLED, id)).isEmpty();
                assertThat(outboxEvents(APPROVED, id)).hasSize(1);
            } else {
                assertThat(status).isEqualTo(202);
                assertThat(requestStatus(id)).isEqualTo("CANCELLED");
                assertThat(ledgerRows(7106)).containsExactly("ACCRUAL:10.00:null", "RESERVATION:-3.00:1",
                        "REVERSAL:3.00:1");
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aTimeoutWaitingForTheEmployeeLockDoesNotHoldBackTheOutboxRelay() throws Exception {
        long id = scenario(7130, "10");
        approve(id, null);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            requestRepository.lockEmployeeCalendar(7130);
            held.countDown();
            awaitLatch(release);
        }));
        try {
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            makeOverdue(id);
            // the background job (default poll 15 s) now waits for the employee lock
            await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(100)).until(() ->
                    jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND pid <> pg_backend_pid() AND wait_event_type = 'Lock' "
                            + "AND wait_event = 'advisory'", Integer.class) > 0);

            seedEmployee(7131);
            long typeId = createLeaveType("Relay " + UUID.randomUUID());
            long other = post("/v1/leaverequests", java.util.Map.of("employeeId", 7131, "leaveTypeId", typeId,
                    "startDate", "2034-02-01", "endDate", "2034-02-02"), JsonNode.class)
                    .getBody().path("leaveRequestId").asLong();

            await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(50)).until(() ->
                    jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id = ? "
                            + "AND event_type = 'leave.request.submitted.v1' AND published_at IS NOT NULL",
                            Integer.class, other) == 1);
        } finally {
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            pool.shutdownNow();
            expire(id);
        }
    }

    @Test
    void aReplyAndTheTimeoutJobRacingInParallelDecideExactlyOnce() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                long employeeId = 7110 + round;
                long id = scenario(employeeId, "10");
                approve(id, null);
                UUID start = startedEventId(id, 1);
                makeOverdue(id);
                CyclicBarrier gate = new CyclicBarrier(2);

                Future<UUID> replying = pool.submit(() -> {
                    gate.await(15, TimeUnit.SECONDS);
                    return reply(COMPLETED_REPLY, id, start, null);
                });
                Future<Integer> timing = pool.submit(() -> {
                    gate.await(15, TimeUnit.SECONDS);
                    return timeoutJob.runOnce();
                });
                UUID reply = replying.get(30, TimeUnit.SECONDS);
                timing.get(30, TimeUnit.SECONDS);
                awaitProcessed(reply);
                // a skipped (locked) round is simply tried again by the next poll
                await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                    timeoutJob.runOnce();
                    assertThat(saga(id, 1).get("saga_status")).isNotEqualTo("STARTED");
                });
                awaitSettled(id);

                String first = (String) saga(id, 1).get("saga_status");
                long reservations = ledgerRows(employeeId).stream().filter(r -> r.startsWith("RESERVATION")).count();
                long reversals = ledgerRows(employeeId).stream().filter(r -> r.startsWith("REVERSAL")).count();
                assertThat(reservations - reversals).as("round %d: one open reservation", round).isEqualTo(1);
                assertThat(balance(employeeId)).as("round %d balance", round).isEqualByComparingTo("7");
                if (first.equals("COMPLETED")) {
                    assertThat(requestStatus(id)).as("round %d", round).isEqualTo("APPROVED");
                    assertThat(reversals).isZero();
                    assertThat(outboxEvents(APPROVED, id)).hasSize(1);
                    assertThat(outboxEvents(REJECTED, id)).isEmpty();
                    assertThat(sagaCount(id)).isEqualTo(1);
                } else {
                    assertThat(first).as("round %d", round).isEqualTo("TIMED_OUT");
                    assertThat(reversals).isEqualTo(1);
                    assertThat(outboxEvents(REJECTED, id)).hasSize(1);
                    assertThat(outboxEvents(APPROVED, id)).isEmpty();
                    assertThat(requestStatus(id)).as("the late completed retried").isEqualTo("APPROVAL_PENDING");
                    assertThat(sagaCount(id)).isEqualTo(2);
                    assertThat(saga(id, 2)).containsEntry("saga_status", "STARTED");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** The request is in a status that no pending message can still change (approved, or retried after a timeout). */
    private void awaitSettled(long id) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(requestStatus(id)).isIn("APPROVED", "APPROVAL_PENDING"));
    }

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
