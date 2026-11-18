package dk.ek.shift_happens.leave;

import dk.ek.shift_happens.leave.application.LeaveLedgerService;
import dk.ek.shift_happens.leave.application.LeaveRequestService;
import dk.ek.shift_happens.leave.domain.InsufficientLeaveBalanceException;
import dk.ek.shift_happens.leave.domain.LeaveRequest;
import dk.ek.shift_happens.leave.domain.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
                        return index % 3 == 0
                                ? ledger.recordManual(employeeId, typeId, TransactionType.ADJUSTMENT,
                                        new BigDecimal("-1.25"), null, null, null)
                                : ledger.recordManual(employeeId, typeId, TransactionType.ACCRUAL,
                                        new BigDecimal("2.5"), null, null, null);
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
                    return ledger.recordManual(employeeId, typeId, TransactionType.ACCRUAL, new BigDecimal("0.5"),
                            null, null, null);
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
                            ledger.reserve(request, null);
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
