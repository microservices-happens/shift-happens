package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.leave.application.LeaveTypeNotFoundException;
import dk.ek.shift_happens.leave.application.LeaveTypeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * A PUT racing a DELETE of the same leave type. PUT and DELETE lock the type's row, so the second one waits for the
 * first and then works on what it committed: the aggregate versions never collide and the tombstone is never undone.
 * Deterministic like LeaveRequestConcurrencyIT: the first transaction is held open on a latch and the test waits
 * (Awaitility) until the second one is blocked on a lock in PostgreSQL.
 */
class LeaveTypeConcurrencyIT extends AbstractCommandIT {

    private static final String UPDATED = "leave.type.updated.v1";
    private static final String DELETED = "leave.type.deleted.v1";

    @Autowired
    private LeaveTypeService leaveTypes;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void aDeleteWaitingForAnUncommittedPutGetsTheNextVersion() throws Exception {
        long id = createLeaveType("PutThenDelete " + UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch changed = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<?> put = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                leaveTypes.replace(id, "Renamed " + UUID.randomUUID(), "d", true, true, null);
                changed.countDown();
                awaitLatch(commit);
            }));
            assertThat(changed.await(15, TimeUnit.SECONDS)).isTrue();

            Future<?> delete = pool.submit(() -> leaveTypes.delete(id, null));
            await().atMost(Duration.ofSeconds(15)).until(() -> delete.isDone() || sessionsBlockedOnALock() > 0);
            commit.countDown();
            put.get(15, TimeUnit.SECONDS);
            delete.get(15, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(outboxEvents(UPDATED, id)).extracting(e -> e.path("aggregateVersion").asLong()).containsExactly(2L);
        List<JsonNode> tombstones = outboxEvents(DELETED, id);
        assertThat(tombstones).extracting(e -> e.path("aggregateVersion").asLong()).containsExactly(3L);
        assertThat(jdbc.queryForMap("SELECT aggregate_version, deleted_at IS NOT NULL AS gone FROM leave_type "
                + "WHERE leave_type_id = ?", id)).containsEntry("aggregate_version", 3L).containsEntry("gone", true);
    }

    @Test
    void aPutWaitingForAnUncommittedDeleteFindsTheTypeGoneAndCannotUndoIt() throws Exception {
        long id = createLeaveType("DeleteThenPut " + UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> put;
        try {
            CountDownLatch deleted = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<?> delete = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
                leaveTypes.delete(id, null);
                deleted.countDown();
                awaitLatch(commit);
            }));
            assertThat(deleted.await(15, TimeUnit.SECONDS)).isTrue();

            put = pool.submit(() -> leaveTypes.replace(id, "Late " + UUID.randomUUID(), null, true, true, null));
            await().atMost(Duration.ofSeconds(15)).until(() -> put.isDone() || sessionsBlockedOnALock() > 0);
            commit.countDown();
            delete.get(15, TimeUnit.SECONDS);
            assertThatThrownBy(() -> put.get(15, TimeUnit.SECONDS))
                    .as("the PUT must see the committed delete and be refused")
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(LeaveTypeNotFoundException.class);
        } finally {
            pool.shutdownNow();
        }

        assertThat(outboxEvents(UPDATED, id)).isEmpty();
        assertThat(outboxEvents(DELETED, id)).extracting(e -> e.path("aggregateVersion").asLong())
                .containsExactly(2L);
        assertThat(jdbc.queryForMap("SELECT aggregate_version, deleted_at IS NOT NULL AS gone FROM leave_type "
                + "WHERE leave_type_id = ?", id)).containsEntry("aggregate_version", 2L).containsEntry("gone", true);
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
