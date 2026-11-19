package dk.ek.shift_happens.leave;

import dk.ek.shift_happens.leave.domain.LeaveRequestSnapshot;
import dk.ek.shift_happens.leave.domain.ReadModelWriter;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Competing consumers (contracts/README.md): two replicas apply events of the same new aggregate at once. The read
 * model must end in the same state as in any serial order.
 */
class LeaveReadModelConcurrencyIT extends AbstractQueryIT {

    private static final int ROUNDS = 200;

    @Autowired
    ReadModelWriter writer;

    @Test
    void concurrentFirstEmployeeUpsertsKeepTheHighestVersion() throws Exception {
        int lost = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ROUNDS; i++) {
                long id = 10_000 + i;
                race(pool, () -> writer.applyEmployee(id, "Old", "Name", 1),
                        () -> writer.applyEmployee(id, "New", "Name", 2));
                Document doc = mongo.findById(id, Document.class, "employees");
                if (((Number) doc.get("version")).longValue() != 2) {
                    lost++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(lost).as("rounds where v2 was lost to a concurrent v1 insert").isZero();
    }

    @Test
    void concurrentFirstRequestUpsertsKeepTheHighestVersion() throws Exception {
        int lost = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ROUNDS; i++) {
                long id = 20_000 + i;
                LeaveRequestSnapshot v1 = objectMapper.treeToValue(envelope(UUID.randomUUID(), Events.SUBMITTED, id, 1,
                        Events.request(id, 1, 1, "Vacation", "2027-03-01", "2027-03-02", "PENDING")).data(),
                        LeaveRequestSnapshot.class);
                LeaveRequestSnapshot v2 = objectMapper.treeToValue(envelope(UUID.randomUUID(), Events.CANCELLED, id, 2,
                        Events.request(id, 1, 1, "Vacation", "2027-03-01", "2027-03-02", "CANCELLED")).data(),
                        LeaveRequestSnapshot.class);
                race(pool, () -> writer.applyLeaveRequest(v1, 1), () -> writer.applyLeaveRequest(v2, 2));
                Document doc = mongo.findById(id, Document.class, "leave_requests");
                if (!"CANCELLED".equals(doc.getString("requestStatus"))) {
                    lost++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(lost).as("rounds where the cancelled snapshot was lost").isZero();
    }

    @Test
    void aConcurrentTombstoneAndFirstSnapshotOfALeaveTypeKeepBoth() throws Exception {
        int wrong = 0;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < ROUNDS; i++) {
                long id = 30_000 + i;
                race(pool, () -> writer.applyLeaveType(new dk.ek.shift_happens.leave.domain.LeaveTypeSnapshot(id,
                                "Name " + id, null, true, true), 1),
                        () -> writer.tombstoneLeaveType(id, 2, Instant.parse("2027-01-01T00:00:00Z")));
                Document doc = mongo.findById(id, Document.class, "leave_types");
                if (!Boolean.TRUE.equals(doc.getBoolean("deleted")) || !("Name " + id).equals(doc.getString("leaveTypeName"))) {
                    wrong++;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(wrong).as("rounds where the tombstone or the snapshot was lost").isZero();
    }

    private static void race(ExecutorService pool, Runnable first, Runnable second) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<?> a = pool.submit(synced(barrier, first));
        Future<?> b = pool.submit(synced(barrier, second));
        a.get();
        b.get();
    }

    private static Callable<Void> synced(CyclicBarrier barrier, Runnable work) {
        return () -> {
            barrier.await();
            work.run();
            return null;
        };
    }
}
