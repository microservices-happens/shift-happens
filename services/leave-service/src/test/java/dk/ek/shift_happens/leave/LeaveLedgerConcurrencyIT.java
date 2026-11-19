package dk.ek.shift_happens.leave;

import dk.ek.shift_happens.leave.application.LeaveLedgerService;
import dk.ek.shift_happens.leave.application.LeaveRequestService;
import dk.ek.shift_happens.leave.domain.InsufficientLeaveBalanceException;
import dk.ek.shift_happens.leave.domain.LeaveRequest;
import dk.ek.shift_happens.leave.domain.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import dk.ek.shift_happens.leave.domain.LeaveRequestRepository;
import dk.ek.shift_happens.leave.domain.LedgerEntry;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parallel appends for one employee and leave type. Every append holds the employee's advisory lock before it reads
 * the balance, so the entry numbers have no gaps or duplicates, each balance_after is the previous one plus the
 * amount, and parallel reservations can never spend more than the balance.
 */
class LeaveLedgerConcurrencyIT extends AbstractCommandIT {

    private static final int PARALLEL = 8;

    @Autowired
    private LeaveLedgerService ledger;
    @Autowired
    private LeaveRequestService requests;
    @Autowired
    private LeaveRequestRepository requestRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Test-only: under shared host load the advisory-lock queue can exceed the 5 s production default; the parallel
     * tests assert chain correctness, LockTimeoutIT asserts the bound. The service's @Transactional joins this one.
     */
    private <V> V withTestLockTimeout(java.util.function.Supplier<V> call) {
        return new TransactionTemplate(transactionManager).execute(s -> {
            jdbc.execute("SET LOCAL lock_timeout = '20s'");
            return call.get();
        });
    }

    @Test
    void parallelAccrualsAndAdjustmentsGetConsecutiveNumbersAndAChainedBalance() throws Exception {
        long typeId = createLeaveType("Chain " + UUID.randomUUID());
        int rounds = 4;
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        try {
            for (int round = 0; round < rounds; round++) {
                long employeeId = 3000 + round;
                seedEmployee(employeeId);
                ledger.recordManual(employeeId, typeId, TransactionType.ACCRUAL, new BigDecimal("1000"), null, null,
                        null);
                int appends = 24;
                CyclicBarrier gate = new CyclicBarrier(PARALLEL);
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < appends; i++) {
                    int index = i;
                    futures.add(pool.submit(() -> {
                        if (index < PARALLEL) {
                            gate.await(15, TimeUnit.SECONDS);
                        }
                        // the mix: accruals of 2.5 and adjustments of -1.25 (the balance never goes below zero)
                        return withTestLockTimeout(() -> index % 3 == 0
                                ? ledger.recordManual(employeeId, typeId, TransactionType.ADJUSTMENT,
                                        new BigDecimal("-1.25"), null, null, null)
                                : ledger.recordManual(employeeId, typeId, TransactionType.ACCRUAL,
                                        new BigDecimal("2.5"), null, null, null));
                    }));
                }
                for (Future<?> future : futures) {
                    future.get(60, TimeUnit.SECONDS);
                }
                assertChain(employeeId, typeId, appends + 1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void parallelAppendsAcrossASnapshotBoundaryKeepTheChainAndWriteOneSnapshot() throws Exception {
        long typeId = createLeaveType("Boundary " + UUID.randomUUID());
        long employeeId = 3100;
        seedEmployee(employeeId);
        ledger.recordManual(employeeId, typeId, TransactionType.ACCRUAL, new BigDecimal("1000"), null, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        try {
            CyclicBarrier gate = new CyclicBarrier(PARALLEL);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    if (index < PARALLEL) {
                        gate.await(15, TimeUnit.SECONDS);
                    }
                    return withTestLockTimeout(() -> ledger.recordManual(employeeId, typeId,
                            TransactionType.ACCRUAL, new BigDecimal("0.5"), null, null, null));
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertChain(employeeId, typeId, 61);
        assertThat(jdbc.queryForList("SELECT entry_number FROM balance_snapshot WHERE employee_id = ? "
                + "AND leave_type_id = ?", Long.class, employeeId, typeId)).containsExactly(50L);
        assertThat(jdbc.queryForObject("SELECT balance_days FROM balance_snapshot WHERE employee_id = ? "
                + "AND leave_type_id = ?", BigDecimal.class, employeeId, typeId)).isEqualByComparingTo("1024.5");
    }

    @Test
    void parallelReservationsTogetherExceedingTheBalanceOnlySucceedAsFarAsTheBalanceReaches() throws Exception {
        long typeId = createLeaveType("Spend " + UUID.randomUUID());
        int rounds = 3;
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        try {
            for (int round = 0; round < rounds; round++) {
                long employeeId = 3200 + round;
                seedEmployee(employeeId);
                ledger.recordManual(employeeId, typeId, TransactionType.ACCRUAL, new BigDecimal("10"), null, null,
                        null);
                // eight distinct 3-day requests (no overlap): 24 days wanted, 10 available, so exactly 3 fit
                List<LeaveRequest> wanted = new ArrayList<>();
                for (int i = 0; i < PARALLEL; i++) {
                    LocalDate start = LocalDate.of(2032, 1, 1).plusDays(i * 10L);
                    wanted.add(requests.submit(employeeId, typeId, start, start.plusDays(2), null, null));
                }

                CyclicBarrier gate = new CyclicBarrier(PARALLEL);
                List<Future<Boolean>> futures = new ArrayList<>();
                for (LeaveRequest request : wanted) {
                    futures.add(pool.submit(() -> {
                        gate.await(15, TimeUnit.SECONDS);
                        try {
                            ledger.reserve(request.leaveRequestId(), 1, null, null);
                            return true;
                        } catch (InsufficientLeaveBalanceException e) {
                            return false;
                        }
                    }));
                }
                int reserved = 0;
                for (Future<Boolean> future : futures) {
                    if (future.get(60, TimeUnit.SECONDS)) {
                        reserved++;
                    }
                }

                assertThat(reserved).as("round %d reservations", round).isEqualTo(3);
                assertThat(jdbc.queryForObject("SELECT SUM(change_amount_days) FROM leave_ledger "
                        + "WHERE employee_id = ? AND leave_type_id = ?", BigDecimal.class, employeeId, typeId))
                        .as("round %d balance", round).isEqualByComparingTo("1");
                assertThat(jdbc.queryForObject("SELECT min(balance_after_days) FROM leave_ledger "
                        + "WHERE employee_id = ? AND leave_type_id = ?", BigDecimal.class, employeeId, typeId))
                        .as("round %d lowest balance", round).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                assertChain(employeeId, typeId, 1 + reserved);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- the saga steps lock the request row themselves, then the employee, and look up after both locks ----

    private long accruedEmployeeWithRequest(long employeeId, long typeId, String from, String to, String accrual) {
        seedEmployee(employeeId);
        ledger.recordManual(employeeId, typeId, TransactionType.ACCRUAL, new BigDecimal(accrual), null, null, null);
        return requests.submit(employeeId, typeId, LocalDate.parse(from), LocalDate.parse(to), null, null)
                .leaveRequestId();
    }

    /**
     * Runs {@code holder} in a transaction that stays open, starts {@code call} on another thread and proves the call
     * waits for a lock the holder owns (it is still running while a session is blocked). Then the holder commits.
     */
    private <T> T callWaitsWhile(Consumer<Long> holder, long holderArgument, Callable<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch commit = new CountDownLatch(1);
            Future<?> a = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                holder.accept(holderArgument);
                held.countDown();
                awaitLatch(commit);
            }));
            assertThat(held.await(15, TimeUnit.SECONDS)).isTrue();
            Future<T> b = pool.submit(call);
            await().atMost(Duration.ofSeconds(15)).until(() -> b.isDone() || sessionsBlockedOnALock() > 0);
            boolean doneEarly = b.isDone();
            commit.countDown();
            a.get(15, TimeUnit.SECONDS);
            T result = b.get(15, TimeUnit.SECONDS);
            assertThat(doneEarly).as("the call must wait for the lock held by the other transaction").isFalse();
            return result;
        } finally {
            pool.shutdownNow();
        }
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

    private int sessionsBlockedOnALock() {
        return jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND pid <> pg_backend_pid() AND wait_event_type = 'Lock'", Integer.class);
    }

    @Test
    void aReverseRacingAnUncommittedReserveWaitsAndThenReversesIt() throws Exception {
        long typeId = createLeaveType("RaceReverse " + UUID.randomUUID());
        long id = accruedEmployeeWithRequest(9107, typeId, "2033-03-01", "2033-03-03", "10");

        Optional<LedgerEntry> reversal = callWaitsWhile(requestId -> {
            requestRepository.findByIdForUpdate(requestId).orElseThrow(); // what the approval path holds
            ledger.reserve(requestId, 1, null, null);
        }, id, () -> ledger.reverseReservation(id, 1, null, null));

        assertThat(reversal).isPresent();
        assertThat(jdbc.queryForList("SELECT transaction_type FROM leave_ledger WHERE employee_id = 9107 "
                + "ORDER BY entry_number", String.class)).containsExactly("ACCRUAL", "RESERVATION", "REVERSAL");
        assertThat(jdbc.queryForObject("SELECT SUM(change_amount_days) FROM leave_ledger WHERE employee_id = 9107",
                BigDecimal.class)).isEqualByComparingTo("10");
        assertChain(9107, typeId, 3);
    }

    @Test
    void reserveWaitsForTheRowLockOfTheRequest() throws Exception {
        long typeId = createLeaveType("RowReserve " + UUID.randomUUID());
        long id = accruedEmployeeWithRequest(9108, typeId, "2033-04-01", "2033-04-03", "10");

        LedgerEntry entry = callWaitsWhile(requestId -> requestRepository.findByIdForUpdate(requestId).orElseThrow(),
                id, () -> ledger.reserve(id, 1, null, null));

        assertThat(entry.transactionType()).isEqualTo(TransactionType.RESERVATION);
        assertChain(9108, typeId, 2);
    }

    @Test
    void reverseWaitsForTheRowLockOfTheRequest() throws Exception {
        long typeId = createLeaveType("RowReverse " + UUID.randomUUID());
        long id = accruedEmployeeWithRequest(9109, typeId, "2033-05-01", "2033-05-03", "10");
        ledger.reserve(id, 1, null, null);

        Optional<LedgerEntry> reversal = callWaitsWhile(
                requestId -> requestRepository.findByIdForUpdate(requestId).orElseThrow(),
                id, () -> ledger.reverseReservation(id, 1, null, null));

        assertThat(reversal).isPresent();
        assertChain(9109, typeId, 3);
    }

    @Test
    void reverseWaitsForTheEmployeeLock() throws Exception {
        long typeId = createLeaveType("EmpReverse " + UUID.randomUUID());
        long id = accruedEmployeeWithRequest(9110, typeId, "2033-06-01", "2033-06-03", "10");
        ledger.reserve(id, 1, null, null);

        Optional<LedgerEntry> reversal = callWaitsWhile(requestRepository::lockEmployeeCalendar, 9110L,
                () -> ledger.reverseReservation(id, 1, null, null));

        assertThat(reversal).isPresent();
        assertChain(9110, typeId, 3);
    }

    @Test
    void eightParallelReversesOfOneReservationAppendExactlyOneReversal() throws Exception {
        long typeId = createLeaveType("ParReverse " + UUID.randomUUID());
        long id = accruedEmployeeWithRequest(9111, typeId, "2033-07-01", "2033-07-03", "10");
        ledger.reserve(id, 1, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        try {
            CyclicBarrier gate = new CyclicBarrier(PARALLEL);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < PARALLEL; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(15, TimeUnit.SECONDS);
                    return ledger.reverseReservation(id, 1, null, null).isPresent();
                }));
            }
            int present = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    present++;
                }
            }
            assertThat(present).as("only one reverse appends, the other seven return empty").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertChain(9111, typeId, 3);
        assertThat(jdbc.queryForObject("SELECT SUM(change_amount_days) FROM leave_ledger WHERE employee_id = 9111",
                BigDecimal.class)).isEqualByComparingTo("10");
    }

    @Test
    void aReverseAmongParallelAccrualsKeepsTheChain() throws Exception {
        long typeId = createLeaveType("MixReverse " + UUID.randomUUID());
        long id = accruedEmployeeWithRequest(9112, typeId, "2033-08-01", "2033-08-03", "10");
        ledger.reserve(id, 1, null, null);
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL);
        try {
            CyclicBarrier gate = new CyclicBarrier(PARALLEL);
            List<Future<?>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                gate.await(15, TimeUnit.SECONDS);
                return ledger.reverseReservation(id, 1, null, null);
            }));
            for (int i = 1; i < PARALLEL; i++) {
                futures.add(pool.submit(() -> {
                    gate.await(15, TimeUnit.SECONDS);
                    return ledger.recordManual(9112, typeId, TransactionType.ACCRUAL, new BigDecimal("2"), null, null,
                            null);
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertChain(9112, typeId, 2 + PARALLEL);
        assertThat(jdbc.queryForObject("SELECT SUM(change_amount_days) FROM leave_ledger WHERE employee_id = 9112",
                BigDecimal.class)).isEqualByComparingTo("24");
    }

    /** Entry numbers are exactly 1..expected and every balance_after is the previous one plus the amount. */
    private void assertChain(long employeeId, long typeId, int expected) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT entry_number, change_amount_days, "
                + "balance_after_days FROM leave_ledger WHERE employee_id = ? AND leave_type_id = ? "
                + "ORDER BY entry_number", employeeId, typeId);
        assertThat(rows).as("entries of employee %d", employeeId).hasSize(expected);
        BigDecimal previous = BigDecimal.ZERO;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            assertThat(row.get("entry_number")).as("entry %d of employee %d", i + 1, employeeId).isEqualTo(i + 1L);
            BigDecimal expectedBalance = previous.add((BigDecimal) row.get("change_amount_days"));
            assertThat((BigDecimal) row.get("balance_after_days")).as("balance after entry %d of employee %d", i + 1,
                    employeeId).isEqualByComparingTo(expectedBalance);
            previous = expectedBalance;
        }
    }
}
