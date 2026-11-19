package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.leave.application.LeaveLedgerService;
import dk.ek.shift_happens.leave.application.LeaveRequestService;
import dk.ek.shift_happens.leave.domain.InsufficientLeaveBalanceException;
import dk.ek.shift_happens.leave.domain.LeaveRequest;
import dk.ek.shift_happens.leave.domain.LeaveRequestRejectedException;
import dk.ek.shift_happens.leave.domain.LedgerEntry;
import dk.ek.shift_happens.leave.domain.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import dk.ek.shift_happens.leave.application.LeaveRequestNotFoundException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ledger service and the database underneath: reserve, reverse, snapshots and the append-only trigger. */
class LeaveLedgerIT extends AbstractCommandIT {

    private static final String RECORDED = "leave.ledger.entry-recorded.v1";

    @Autowired
    private LeaveLedgerService ledger;
    @Autowired
    private LeaveRequestService requests;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private LeaveRequest request(long employeeId, long typeId, String from, String to) {
        return requests.submit(employeeId, typeId, LocalDate.parse(from), LocalDate.parse(to), null, null);
    }

    private BigDecimal sum(long employeeId, long typeId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(change_amount_days), 0) FROM leave_ledger "
                + "WHERE employee_id = ? AND leave_type_id = ?", BigDecimal.class, employeeId, typeId);
    }

    private int rows(long employeeId, long typeId) {
        return jdbc.queryForObject("SELECT count(*) FROM leave_ledger WHERE employee_id = ? AND leave_type_id = ?",
                Integer.class, employeeId, typeId);
    }

    @Test
    void reservingTakesTheDaysOfTheRequestAndPublishesTheEntry() {
        long typeId = createLeaveType("Reserve " + UUID.randomUUID());
        seedEmployee(4001);
        ledger.recordManual(4001, typeId, TransactionType.ACCRUAL, new BigDecimal("10"), null, null, null);
        // 2031-05-02 is a Friday, 2031-05-05 a Monday: 4 calendar days
        LeaveRequest request = request(4001, typeId, "2031-05-02", "2031-05-05");

        LedgerEntry entry = ledger.reserve(request.leaveRequestId(), 1, "corr-1", null);

        assertThat(entry.transactionType()).isEqualTo(TransactionType.RESERVATION);
        assertThat(entry.changeAmountDays()).isEqualByComparingTo("-4");
        assertThat(entry.balanceAfterDays()).isEqualByComparingTo("6");
        assertThat(entry.entryNumber()).isEqualTo(2);
        assertThat(entry.referenceEntityType()).isEqualTo("LEAVE_REQUEST");
        assertThat(entry.referenceEntityId()).isEqualTo(request.leaveRequestId());
        assertThat(sum(4001, typeId)).isEqualByComparingTo("6");

        List<JsonNode> events = outboxEvents(RECORDED, entry.leaveLedgerId());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).path("correlationId").asText()).isEqualTo("corr-1");
        JsonNode data = events.get(0).path("data");
        assertThat(data.path("transactionType").asText()).isEqualTo("RESERVATION");
        assertThat(data.path("changeAmountDays").decimalValue()).isEqualByComparingTo("-4");
        assertThat(data.path("balanceAfterDays").decimalValue()).isEqualByComparingTo("6");
        assertThat(data.path("referenceEntityType").asText()).isEqualTo("LEAVE_REQUEST");
        assertThat(data.path("referenceEntityId").asLong()).isEqualTo(request.leaveRequestId());
    }

    @Test
    void reservingWithTooLittleBalanceThrowsAndWritesNothing() {
        long typeId = createLeaveType("TooLittle " + UUID.randomUUID());
        seedEmployee(4002);
        ledger.recordManual(4002, typeId, TransactionType.ACCRUAL, new BigDecimal("3.5"), null, null, null);
        LeaveRequest request = request(4002, typeId, "2031-06-02", "2031-06-05"); // 4 days
        int events = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class,
                RECORDED);

        assertThatThrownBy(() -> ledger.reserve(request.leaveRequestId(), 1, null, null)).isInstanceOf(InsufficientLeaveBalanceException.class);

        assertThat(rows(4002, typeId)).isEqualTo(1);
        assertThat(sum(4002, typeId)).isEqualByComparingTo("3.5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = ?", Integer.class,
                RECORDED)).isEqualTo(events);
    }

    @Test
    void aRequestIsReservedOnlyOnce() {
        long typeId = createLeaveType("Twice " + UUID.randomUUID());
        seedEmployee(4003);
        ledger.recordManual(4003, typeId, TransactionType.ACCRUAL, new BigDecimal("10"), null, null, null);
        LeaveRequest request = request(4003, typeId, "2031-07-01", "2031-07-02");
        ledger.reserve(request.leaveRequestId(), 1, null, null);

        assertThatThrownBy(() -> ledger.reserve(request.leaveRequestId(), 1, null, null)).isInstanceOf(LeaveRequestRejectedException.class);

        assertThat(sum(4003, typeId)).isEqualByComparingTo("8");
    }

    @Test
    void reversingRestoresTheBalanceAndASecondReverseAppendsNothing() {
        long typeId = createLeaveType("Reverse " + UUID.randomUUID());
        seedEmployee(4004);
        ledger.recordManual(4004, typeId, TransactionType.ACCRUAL, new BigDecimal("10"), null, null, null);
        LeaveRequest request = request(4004, typeId, "2031-08-01", "2031-08-03");
        ledger.reserve(request.leaveRequestId(), 1, null, null);

        Optional<LedgerEntry> reversal = ledger.reverseReservation(request.leaveRequestId(), 1, "corr-2", null);
        Optional<LedgerEntry> again = ledger.reverseReservation(request.leaveRequestId(), 1, "corr-3", null);

        assertThat(reversal).isPresent();
        LedgerEntry entry = reversal.get();
        assertThat(entry.transactionType()).isEqualTo(TransactionType.REVERSAL);
        assertThat(entry.changeAmountDays()).isEqualByComparingTo("3");
        assertThat(entry.balanceAfterDays()).isEqualByComparingTo("10");
        assertThat(entry.entryNumber()).isEqualTo(3);
        assertThat(entry.referenceEntityType()).isEqualTo("LEAVE_REQUEST");
        assertThat(entry.referenceEntityId()).isEqualTo(request.leaveRequestId());
        assertThat(again).isEmpty();
        assertThat(rows(4004, typeId)).isEqualTo(3);
        assertThat(sum(4004, typeId)).isEqualByComparingTo("10");
        assertThat(outboxEvents(RECORDED, entry.leaveLedgerId())).hasSize(1);
    }

    @Test
    void reversingARequestThatWasNeverReservedDoesNothing() {
        long typeId = createLeaveType("NeverReserved " + UUID.randomUUID());
        seedEmployee(4005);
        LeaveRequest request = request(4005, typeId, "2031-09-01", "2031-09-02");

        assertThat(ledger.reverseReservation(request.leaveRequestId(), 1, null, null)).isEmpty();
        assertThat(ledger.reverseReservation(99_999_999L, 1, null, null)).isEmpty();
        assertThat(rows(4005, typeId)).isZero();
    }

    @Test
    void theDatabaseAllowsOneReservationAndOneReversalPerSagaAttempt() {
        long typeId = createLeaveType("Backstop " + UUID.randomUUID());
        seedEmployee(4006);
        ledger.recordManual(4006, typeId, TransactionType.ACCRUAL, new BigDecimal("10"), null, null, null);
        LeaveRequest request = request(4006, typeId, "2031-10-01", "2031-10-02");
        ledger.reserve(request.leaveRequestId(), 1, null, null);
        ledger.reverseReservation(request.leaveRequestId(), 1, null, null);
        String insert = "INSERT INTO leave_ledger (employee_id, leave_type_id, entry_number, change_amount_days, "
                + "balance_after_days, transaction_type, reference_entity_type, reference_entity_id, "
                + "transaction_datetime, saga_attempt) VALUES (4006, ?, 4, ?, 7, ?, 'LEAVE_REQUEST', ?, now(), 1)";

        assertThatThrownBy(() -> jdbc.update(insert, typeId, -1, "RESERVATION", request.leaveRequestId()))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("ux_leave_ledger_one_reservation");
        assertThatThrownBy(() -> jdbc.update(insert, typeId, 1, "REVERSAL", request.leaveRequestId()))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("ux_leave_ledger_one_reversal");
        assertThat(rows(4006, typeId)).isEqualTo(3);
    }

    @Test
    void aLaterSagaAttemptReservesAndReversesAgainAndEachAttemptOnlyOnce() {
        long typeId = createLeaveType("Attempts " + UUID.randomUUID());
        seedEmployee(4012);
        ledger.recordManual(4012, typeId, TransactionType.ACCRUAL, new BigDecimal("10"), null, null, null);
        LeaveRequest request = request(4012, typeId, "2032-02-01", "2032-02-02");
        long id = request.leaveRequestId();
        ledger.reserve(id, 1, null, null);
        ledger.reverseReservation(id, 1, null, null);

        ledger.reserve(id, 2, null, null);

        assertThat(sum(4012, typeId)).as("attempt 2 holds the days again").isEqualByComparingTo("8");
        assertThatThrownBy(() -> ledger.reserve(id, 2, null, null)).isInstanceOf(LeaveRequestRejectedException.class)
                .hasMessageContaining("attempt 2");
        assertThat(ledger.reverseReservation(id, 1, null, null)).as("attempt 1 is already reversed").isEmpty();
        assertThat(ledger.reverseReservation(id, 3, null, null)).as("attempt 3 was never reserved").isEmpty();
        assertThat(ledger.reverseReservation(id, 2, null, null)).isPresent();
        assertThat(sum(4012, typeId)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForList("SELECT transaction_type || saga_attempt FROM leave_ledger "
                + "WHERE employee_id = 4012 AND saga_attempt IS NOT NULL ORDER BY entry_number", String.class))
                .containsExactly("RESERVATION1", "REVERSAL1", "RESERVATION2", "REVERSAL2");
    }

    @Test
    void aReserveOfAnUnknownRequestIsNotFound() {
        assertThatThrownBy(() -> ledger.reserve(99_999_999L, 1, null, null))
                .isInstanceOf(LeaveRequestNotFoundException.class);
    }

    @Test
    void aRefusedReserveInsideTheCallersTransactionDoesNotMarkItRollbackOnly() {
        long typeId = createLeaveType("NoRollback " + UUID.randomUUID());
        seedEmployee(4010);
        LeaveRequest tooBig = request(4010, typeId, "2031-11-03", "2031-11-05"); // 3 days, balance 0
        LeaveRequest affordable = request(4010, typeId, "2031-12-01", "2031-12-01"); // 1 day
        ledger.recordManual(4010, typeId, TransactionType.ACCRUAL, new BigDecimal("1"), null, null, null);
        ledger.reserve(affordable.leaveRequestId(), 1, null, null);
        String sentinel = "Sentinel " + UUID.randomUUID();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThatThrownBy(() -> ledger.reserve(tooBig.leaveRequestId(), 1, null, null))
                    .isInstanceOf(InsufficientLeaveBalanceException.class);
            assertThatThrownBy(() -> ledger.reserve(affordable.leaveRequestId(), 1, null, null))
                    .isInstanceOf(LeaveRequestRejectedException.class);
            jdbc.update("INSERT INTO leave_type (leave_type_name) VALUES (?)", sentinel);
        });

        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_type WHERE leave_type_name = ?", Integer.class,
                sentinel)).as("the caller's transaction committed").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_ledger WHERE employee_id = 4010 "
                + "AND transaction_type = 'RESERVATION'", Integer.class)).isEqualTo(1);
    }

    @Test
    void theSagaEntriesCarryTheCausationAndTheCorrelationOfTheirEvent() {
        long typeId = createLeaveType("Causation " + UUID.randomUUID());
        seedEmployee(4011);
        ledger.recordManual(4011, typeId, TransactionType.ACCRUAL, new BigDecimal("10"), null, null, "corr-m");
        LeaveRequest request = request(4011, typeId, "2032-01-05", "2032-01-06");
        UUID cause = UUID.randomUUID();
        UUID reverseCause = UUID.randomUUID();

        LedgerEntry reserved = ledger.reserve(request.leaveRequestId(), 1, "corr-r", cause);
        LedgerEntry reversed = ledger.reverseReservation(request.leaveRequestId(), 1, "corr-v", reverseCause).orElseThrow();

        JsonNode reserveEvent = outboxEvents(RECORDED, reserved.leaveLedgerId()).get(0);
        assertThat(reserveEvent.path("eventType").asText()).isEqualTo(RECORDED);
        assertThat(reserveEvent.path("aggregateId").asLong()).isEqualTo(reserved.leaveLedgerId());
        assertThat(reserveEvent.path("correlationId").asText()).isEqualTo("corr-r");
        assertThat(uuidOf(reserveEvent.path("causationId"))).isEqualTo(cause);
        JsonNode reverseEvent = outboxEvents(RECORDED, reversed.leaveLedgerId()).get(0);
        assertThat(reverseEvent.path("correlationId").asText()).isEqualTo("corr-v");
        assertThat(uuidOf(reverseEvent.path("causationId"))).isEqualTo(reverseCause);
    }

    @Test
    void snapshotsAreWrittenAtFiftyAndOneHundredAndTheBalanceMatchesThePlainSum() {
        long typeId = createLeaveType("Snapshots " + UUID.randomUUID());
        seedEmployee(4007);
        String[] amounts = {"5.25", "-0.75", "1.5", "-1", "2"};
        for (int i = 0; i < 120; i++) {
            ledger.recordManual(4007, typeId, TransactionType.ADJUSTMENT, new BigDecimal(amounts[i % 5]), null,
                    null, null);
        }

        List<Long> snapshotNumbers = jdbc.queryForList("SELECT entry_number FROM balance_snapshot "
                + "WHERE employee_id = 4007 AND leave_type_id = ? ORDER BY entry_number", Long.class, typeId);
        assertThat(snapshotNumbers).containsExactly(50L, 100L);
        for (long number : snapshotNumbers) {
            assertThat(jdbc.queryForObject("SELECT s.balance_days = l.balance_after_days AND "
                            + "s.balance_days = (SELECT SUM(change_amount_days) FROM leave_ledger "
                            + "WHERE employee_id = 4007 AND leave_type_id = ? AND entry_number <= ?) "
                            + "FROM balance_snapshot s JOIN leave_ledger l ON l.employee_id = s.employee_id "
                            + "AND l.leave_type_id = s.leave_type_id AND l.entry_number = s.entry_number "
                            + "WHERE s.employee_id = 4007 AND s.leave_type_id = ? AND s.entry_number = ?",
                    Boolean.class, typeId, number, typeId, number)).as("snapshot %d", number).isTrue();
        }
        assertThat(jdbc.queryForList("SELECT entry_number FROM leave_ledger WHERE employee_id = 4007 "
                + "AND leave_type_id = ? ORDER BY entry_number", Long.class, typeId))
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1, 120).boxed().toList());
        BigDecimal plain = sum(4007, typeId);
        assertThat(jdbc.queryForObject("SELECT balance_after_days FROM leave_ledger WHERE employee_id = 4007 "
                + "AND leave_type_id = ? AND entry_number = 120", BigDecimal.class, typeId))
                .isEqualByComparingTo(plain);

        // the next entry builds on snapshot 100 + 20 entries and still agrees with the plain sum
        LedgerEntry next = ledger.recordManual(4007, typeId, TransactionType.ACCRUAL, new BigDecimal("1"), null,
                null, null);
        assertThat(next.entryNumber()).isEqualTo(121);
        assertThat(next.balanceAfterDays()).isEqualByComparingTo(plain.add(BigDecimal.ONE));
    }

    @Test
    void theBalanceIsReadFromTheSnapshotEvenWhenTheEarlierEntriesAreGone() {
        // Proves the snapshot path of the service without a query counter: poison the snapshot at entry 50 in a
        // scratch pair and see that the next balance starts from it and not from the sum of the entries.
        long typeId = createLeaveType("Poison " + UUID.randomUUID());
        seedEmployee(4008);
        for (int i = 0; i < 50; i++) {
            ledger.recordManual(4008, typeId, TransactionType.ACCRUAL, BigDecimal.ONE, null, null, null);
        }
        assertThat(jdbc.queryForObject("SELECT balance_days FROM balance_snapshot WHERE employee_id = 4008 "
                + "AND entry_number = 50", BigDecimal.class)).isEqualByComparingTo("50");
        // The append-only trigger makes the snapshot unchangeable. ALTER TABLE ... DISABLE TRIGGER is transactional,
        // so disable, update and enable inside one transaction: no other session ever sees the trigger disabled.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.execute("ALTER TABLE balance_snapshot DISABLE TRIGGER trg_balance_snapshot_append_only");
            jdbc.update("UPDATE balance_snapshot SET balance_days = 1000 WHERE employee_id = 4008");
            jdbc.execute("ALTER TABLE balance_snapshot ENABLE TRIGGER trg_balance_snapshot_append_only");
        });

        LedgerEntry next = ledger.recordManual(4008, typeId, TransactionType.ACCRUAL, BigDecimal.ONE, null, null,
                null);

        assertThat(next.entryNumber()).isEqualTo(51);
        assertThat(next.balanceAfterDays()).as("snapshot (1000) + the entry after it, not the 51 plain entries")
                .isEqualByComparingTo("1001");
    }

    @Test
    void theDatabaseRefusesToUpdateOrDeleteLedgerEntries() {
        long typeId = createLeaveType("Immutable " + UUID.randomUUID());
        seedEmployee(4009);
        for (int i = 0; i < 50; i++) {
            ledger.recordManual(4009, typeId, TransactionType.ACCRUAL, BigDecimal.ONE, null, null, null);
        }
        long id = jdbc.queryForObject("SELECT leave_ledger_id FROM leave_ledger WHERE employee_id = 4009 "
                + "AND entry_number = 1", Long.class);

        assertThatThrownBy(() -> jdbc.update("UPDATE leave_ledger SET change_amount_days = 99 "
                + "WHERE leave_ledger_id = ?", id)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM leave_ledger WHERE leave_ledger_id = ?", id))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE balance_snapshot SET balance_days = 1 "
                + "WHERE employee_id = 4009")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM balance_snapshot WHERE employee_id = 4009"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE leave_ledger")).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE balance_snapshot")).hasMessageContaining("append-only");

        assertThat(rows(4009, typeId)).isEqualTo(50);
        assertThat(sum(4009, typeId)).isEqualByComparingTo("50");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM balance_snapshot WHERE employee_id = 4009",
                Integer.class)).isEqualTo(1);
    }
}
