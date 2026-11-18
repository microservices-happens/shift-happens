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

        LedgerEntry entry = ledger.reserve(request, "corr-1");

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

        assertThatThrownBy(() -> ledger.reserve(request, null)).isInstanceOf(InsufficientLeaveBalanceException.class);

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
        ledger.reserve(request, null);

        assertThatThrownBy(() -> ledger.reserve(request, null)).isInstanceOf(LeaveRequestRejectedException.class);

        assertThat(sum(4003, typeId)).isEqualByComparingTo("8");
    }

    @Test
    void reversingRestoresTheBalanceAndASecondReverseAppendsNothing() {
        long typeId = createLeaveType("Reverse " + UUID.randomUUID());
        seedEmployee(4004);
        ledger.recordManual(4004, typeId, TransactionType.ACCRUAL, new BigDecimal("10"), null, null, null);
        LeaveRequest request = request(4004, typeId, "2031-08-01", "2031-08-03");
        ledger.reserve(request, null);

        Optional<LedgerEntry> reversal = ledger.reverseReservation(request.leaveRequestId(), "corr-2");
        Optional<LedgerEntry> again = ledger.reverseReservation(request.leaveRequestId(), "corr-3");

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

        assertThat(ledger.reverseReservation(request.leaveRequestId(), null)).isEmpty();
        assertThat(ledger.reverseReservation(99_999_999L, null)).isEmpty();
        assertThat(rows(4005, typeId)).isZero();
    }

    @Test
    void theDatabaseAllowsOneReservationAndOneReversalPerRequest() {
        long typeId = createLeaveType("Backstop " + UUID.randomUUID());
        seedEmployee(4006);
        ledger.recordManual(4006, typeId, TransactionType.ACCRUAL, new BigDecimal("10"), null, null, null);
        LeaveRequest request = request(4006, typeId, "2031-10-01", "2031-10-02");
        ledger.reserve(request, null);

        assertThatThrownBy(() -> jdbc.update("INSERT INTO leave_ledger (employee_id, leave_type_id, entry_number, "
                + "change_amount_days, balance_after_days, transaction_type, reference_entity_type, "
                + "reference_entity_id, transaction_datetime) VALUES (4006, ?, 3, -1, 7, 'RESERVATION', "
                + "'LEAVE_REQUEST', ?, now())", typeId, request.leaveRequestId()))
                .isInstanceOf(DataAccessException.class);
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
        // the append-only trigger makes the snapshot unchangeable, so a session-level bypass is needed
        jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("ALTER TABLE balance_snapshot DISABLE TRIGGER trg_balance_snapshot_append_only");
                try {
                    statement.execute("UPDATE balance_snapshot SET balance_days = 1000 WHERE employee_id = 4008");
                } finally {
                    statement.execute("ALTER TABLE balance_snapshot ENABLE TRIGGER trg_balance_snapshot_append_only");
                }
            }
            return null;
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

        assertThat(rows(4009, typeId)).isEqualTo(50);
        assertThat(sum(4009, typeId)).isEqualByComparingTo("50");
    }
}
