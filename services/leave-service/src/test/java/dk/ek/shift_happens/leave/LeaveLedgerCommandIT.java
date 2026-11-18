package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** POST /v1/leaveledgers: manual ACCRUAL and ADJUSTMENT entries, state in Postgres, events in the outbox. */
class LeaveLedgerCommandIT extends AbstractCommandIT {

    private static final String RECORDED = "leave.ledger.entry-recorded.v1";

    private static Map<String, Object> entry(long employeeId, long typeId, Object amount, String type) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("employeeId", employeeId);
        body.put("leaveTypeId", typeId);
        body.put("changeAmountDays", amount);
        body.put("transactionType", type);
        return body;
    }

    private BigDecimal balance(long employeeId, long typeId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(change_amount_days), 0) FROM leave_ledger "
                + "WHERE employee_id = ? AND leave_type_id = ?", BigDecimal.class, employeeId, typeId);
    }

    private int rows(long employeeId, long typeId) {
        return jdbc.queryForObject("SELECT count(*) FROM leave_ledger WHERE employee_id = ? AND leave_type_id = ?",
                Integer.class, employeeId, typeId);
    }

    private long accrue(long employeeId, long typeId, String amount) {
        ResponseEntity<JsonNode> response = post("/v1/leaveledgers", entry(employeeId, typeId, new BigDecimal(amount),
                "ACCRUAL"), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return response.getBody().path("leaveLedgerId").asLong();
    }

    @Test
    void anAccrualIsStoredAndPublishedWithTheContractShape() {
        long typeId = createLeaveType("Accrual " + UUID.randomUUID());
        seedEmployee(2001);
        String correlationId = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> response = http.postForEntity("/v1/leaveledgers", new HttpEntity<>(
                Map.of("employeeId", 2001, "leaveTypeId", typeId, "changeAmountDays", 12.5,
                        "transactionType", "ACCRUAL", "referenceEntityType", "PAYROLL", "referenceEntityId", 77),
                headersFor(UUID.randomUUID().toString(), correlationId)), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        JsonNode created = response.getBody();
        assertThat(created.fieldNames()).toIterable().containsExactlyInAnyOrder("leaveLedgerId", "employeeId",
                "leaveTypeId", "changeAmountDays", "transactionType", "referenceEntityType", "referenceEntityId",
                "transactionDatetime");
        long id = created.path("leaveLedgerId").asLong();
        assertThat(created.path("changeAmountDays").isNumber()).isTrue();
        assertThat(created.path("changeAmountDays").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(created.path("transactionType").asText()).isEqualTo("ACCRUAL");
        assertThat(created.path("referenceEntityType").asText()).isEqualTo("PAYROLL");
        assertThat(created.path("referenceEntityId").asLong()).isEqualTo(77);
        assertThat(instantOf(created.path("transactionDatetime"))).isNotNull();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM leave_ledger WHERE leave_ledger_id = ?", id);
        assertThat(row.get("entry_number")).isEqualTo(1L);
        assertThat((BigDecimal) row.get("change_amount_days")).isEqualByComparingTo("12.50");
        assertThat((BigDecimal) row.get("balance_after_days")).isEqualByComparingTo("12.50");

        List<JsonNode> events = outboxEvents(RECORDED, id);
        assertThat(events).hasSize(1);
        JsonNode envelope = events.get(0);
        assertThat(envelope.path("aggregateVersion").asLong()).isEqualTo(1);
        assertThat(envelope.path("producer").asText()).isEqualTo("leave-service");
        assertThat(envelope.path("correlationId").asText()).isEqualTo(correlationId);
        assertThat(uuidOf(envelope.path("eventId"))).isNotNull();
        JsonNode data = envelope.path("data");
        assertThat(data.fieldNames()).toIterable().containsExactlyInAnyOrder("leaveLedgerId", "employeeId",
                "leaveTypeId", "changeAmountDays", "balanceAfterDays", "transactionType", "referenceEntityType",
                "referenceEntityId", "transactionDatetime");
        assertThat(data.path("leaveLedgerId").isIntegralNumber()).isTrue();
        assertThat(data.path("leaveLedgerId").asLong()).isEqualTo(id);
        assertThat(data.path("employeeId").asLong()).isEqualTo(2001);
        assertThat(data.path("leaveTypeId").asLong()).isEqualTo(typeId);
        assertThat(data.path("changeAmountDays").isNumber()).as("a JSON number, not a string").isTrue();
        assertThat(data.path("changeAmountDays").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(data.path("balanceAfterDays").isNumber()).isTrue();
        assertThat(data.path("balanceAfterDays").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(data.path("transactionType").asText()).isEqualTo("ACCRUAL");
        assertThat(data.path("referenceEntityType").asText()).isEqualTo("PAYROLL");
        assertThat(data.path("referenceEntityId").asLong()).isEqualTo(77);
        assertThat(instantOf(data.path("transactionDatetime"))).isEqualTo(instantOf(created.path("transactionDatetime")));
    }

    @Test
    void optionalReferencesAreNullInTheEventWhenOmitted() {
        long typeId = createLeaveType("NoRef " + UUID.randomUUID());
        seedEmployee(2002);

        long id = accrue(2002, typeId, "3");

        JsonNode data = outboxEvents(RECORDED, id).get(0).path("data");
        assertThat(data.path("referenceEntityType").isNull()).isTrue();
        assertThat(data.path("referenceEntityId").isNull()).isTrue();
    }

    @Test
    void anAdjustmentWithinTheBalanceIsAcceptedAndNumbersTheEntriesInOrder() {
        long typeId = createLeaveType("Adjust " + UUID.randomUUID());
        seedEmployee(2003);
        accrue(2003, typeId, "10");

        ResponseEntity<JsonNode> response = post("/v1/leaveledgers",
                entry(2003, typeId, new BigDecimal("-4.25"), "ADJUSTMENT"), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(balance(2003, typeId)).isEqualByComparingTo("5.75");
        long id = response.getBody().path("leaveLedgerId").asLong();
        assertThat(jdbc.queryForMap("SELECT entry_number, balance_after_days FROM leave_ledger "
                + "WHERE leave_ledger_id = ?", id))
                .containsEntry("entry_number", 2L).hasEntrySatisfying("balance_after_days",
                        v -> assertThat((BigDecimal) v).isEqualByComparingTo("5.75"));
        assertThat(outboxEvents(RECORDED, id).get(0).path("data").path("balanceAfterDays").decimalValue())
                .isEqualByComparingTo("5.75");
    }

    @Test
    void anAdjustmentBelowZeroIsAConflictAndWritesNothing() {
        long typeId = createLeaveType("Negative " + UUID.randomUUID());
        seedEmployee(2004);
        accrue(2004, typeId, "2");
        int outboxBefore = jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = ? "
                + "AND payload::jsonb -> 'data' ->> 'employeeId' = '2004'", Integer.class, RECORDED);

        ResponseEntity<String> response = post("/v1/leaveledgers",
                entry(2004, typeId, new BigDecimal("-2.01"), "ADJUSTMENT"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(response.getBody()).contains("Insufficient leave balance");
        assertThat(rows(2004, typeId)).isEqualTo(1);
        assertThat(balance(2004, typeId)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_type = ? "
                + "AND payload::jsonb -> 'data' ->> 'employeeId' = '2004'", Integer.class, RECORDED))
                .isEqualTo(outboxBefore);
    }

    @Test
    void aNegativeFirstEntryIsAConflictBecauseTheBalanceStartsAtZero() {
        long typeId = createLeaveType("First " + UUID.randomUUID());
        seedEmployee(2005);

        ResponseEntity<String> response = post("/v1/leaveledgers",
                entry(2005, typeId, new BigDecimal("-1"), "ADJUSTMENT"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rows(2005, typeId)).isZero();
    }

    @Test
    void theBalanceIsKeptPerLeaveType() {
        long vacation = createLeaveType("TypeA " + UUID.randomUUID());
        long sick = createLeaveType("TypeB " + UUID.randomUUID());
        seedEmployee(2006);
        accrue(2006, vacation, "5");

        ResponseEntity<String> response = post("/v1/leaveledgers",
                entry(2006, sick, new BigDecimal("-1"), "ADJUSTMENT"), String.class);

        assertThat(response.getStatusCode()).as("the vacation balance cannot pay for sick leave")
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void reservationAndReversalCannotBePostedByHand() {
        long typeId = createLeaveType("Manual " + UUID.randomUUID());
        seedEmployee(2007);
        accrue(2007, typeId, "10");

        ResponseEntity<String> reservation = post("/v1/leaveledgers",
                entry(2007, typeId, new BigDecimal("-1"), "RESERVATION"), String.class);
        ResponseEntity<String> reversal = post("/v1/leaveledgers",
                entry(2007, typeId, new BigDecimal("1"), "REVERSAL"), String.class);

        assertThat(reservation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reversal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(reservation.getBody()).contains("saga");
        assertThat(rows(2007, typeId)).isEqualTo(1);
    }

    @Test
    void invalidAmountsTypesAndBodiesAreBadRequests() {
        long typeId = createLeaveType("Invalid " + UUID.randomUUID());
        seedEmployee(2008);

        List<Map<String, Object>> bodies = List.of(
                entry(2008, typeId, 0, "ACCRUAL"),
                entry(2008, typeId, new BigDecimal("0.00"), "ADJUSTMENT"),
                entry(2008, typeId, new BigDecimal("1.005"), "ACCRUAL"),
                entry(2008, typeId, new BigDecimal("-0.001"), "ADJUSTMENT"),
                entry(2008, typeId, -1, "ACCRUAL"),
                entry(2008, typeId, 100000, "ACCRUAL"),
                entry(2008, typeId, 1, "USAGE"),
                entry(2008, typeId, 1, null),
                entry(2008, typeId, null, "ACCRUAL"),
                entry(0, typeId, 1, "ACCRUAL"),
                Map.of("employeeId", 2008));
        for (Map<String, Object> body : bodies) {
            ResponseEntity<String> response = post("/v1/leaveledgers", body, String.class);

            assertThat(response.getStatusCode()).as("%s: %s", body, response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        }
        assertThat(rows(2008, typeId)).isZero();
    }

    @Test
    void twoDecimalsAndTrailingZerosAreAccepted() {
        long typeId = createLeaveType("Decimals " + UUID.randomUUID());
        seedEmployee(2009);

        accrue(2009, typeId, "0.01");
        accrue(2009, typeId, "1.500");

        assertThat(balance(2009, typeId)).isEqualByComparingTo("1.51");
    }

    @Test
    void anUnknownLeaveTypeIsABadRequest() {
        seedEmployee(2010);

        ResponseEntity<String> response = post("/v1/leaveledgers",
                entry(2010, 99_999_999L, new BigDecimal("1"), "ACCRUAL"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("unknown leaveTypeId");
    }

    @Test
    void anUnknownInactiveOrTombstonedEmployeeIsAConflict() {
        long typeId = createLeaveType("Replica " + UUID.randomUUID());
        seedEmployee(2011, "INACTIVE", false);
        seedEmployee(2012, "ACTIVE", true);

        for (long employeeId : new long[] {2011, 2012, 2013}) {
            ResponseEntity<String> response = post("/v1/leaveledgers",
                    entry(employeeId, typeId, new BigDecimal("1"), "ACCRUAL"), String.class);

            assertThat(response.getStatusCode()).as("employee %s", employeeId).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).contains("employee " + employeeId).contains("replica");
            assertThat(rows(employeeId, typeId)).isZero();
        }
    }

    @Test
    void aReplayedIdempotencyKeyReturnsTheSameBodyAndWritesOnce() {
        long typeId = createLeaveType("Replay " + UUID.randomUUID());
        seedEmployee(2014);
        String key = UUID.randomUUID().toString();
        Map<String, Object> body = entry(2014, typeId, new BigDecimal("4"), "ACCRUAL");

        ResponseEntity<String> first = http.postForEntity("/v1/leaveledgers",
                new HttpEntity<>(body, headersFor(key, null)), String.class);
        ResponseEntity<String> second = http.postForEntity("/v1/leaveledgers",
                new HttpEntity<>(body, headersFor(key, null)), String.class);
        ResponseEntity<String> different = http.postForEntity("/v1/leaveledgers",
                new HttpEntity<>(entry(2014, typeId, new BigDecimal("5"), "ACCRUAL"), headersFor(key, null)),
                String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(different.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(rows(2014, typeId)).isEqualTo(1);
        long id = jdbc.queryForObject("SELECT leave_ledger_id FROM leave_ledger WHERE employee_id = 2014 "
                + "AND leave_type_id = ?", Long.class, typeId);
        assertThat(outboxEvents(RECORDED, id)).hasSize(1);
    }

    @Test
    void aMissingIdempotencyKeyIsABadRequest() {
        long typeId = createLeaveType("NoKey " + UUID.randomUUID());
        seedEmployee(2015);

        ResponseEntity<String> response = http.postForEntity("/v1/leaveledgers",
                new HttpEntity<>(entry(2015, typeId, 1, "ACCRUAL"), headersFor(null, null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rows(2015, typeId)).isZero();
    }

    @Test
    void theLedgerIsAppendOnlyOverHttp() {
        long typeId = createLeaveType("Append " + UUID.randomUUID());
        seedEmployee(2016);
        long id = accrue(2016, typeId, "1");

        for (HttpMethod method : new HttpMethod[] {HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE}) {
            for (String path : new String[] {"/v1/leaveledgers/" + id, "/v1/leaveledgers"}) {
                ResponseEntity<String> response = http.exchange(path, method,
                        new HttpEntity<>(entry(2016, typeId, 2, "ACCRUAL"), headersFor(UUID.randomUUID().toString(), null)),
                        String.class);

                assertThat(response.getStatusCode().is2xxSuccessful()).as("%s %s", method, path).isFalse();
            }
        }
        // and reads belong to the query role
        assertThat(http.getForEntity("/v1/leaveledgers/" + id, String.class).getStatusCode().is2xxSuccessful())
                .isFalse();
        assertThat(rows(2016, typeId)).isEqualTo(1);
        assertThat(balance(2016, typeId)).isEqualByComparingTo("1");
    }

    @Test
    void aLeaveTypeWithLedgerEntriesCannotBeDeleted() {
        long typeId = createLeaveType("Referenced " + UUID.randomUUID());
        seedEmployee(2017);
        accrue(2017, typeId, "1");

        ResponseEntity<String> response = delete("/v1/leavetypes/" + typeId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM leave_type WHERE leave_type_id = ?", Integer.class,
                typeId)).isEqualTo(1);
        assertThat(rows(2017, typeId)).isEqualTo(1);
    }
}
