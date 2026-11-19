package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.leave.infrastructure.messaging.ProjectionEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every GET of the query role, answered from the Mongo read model that the events fill. */
class LeaveQueryApiIT extends AbstractQueryIT {

    // ---- leave types ----

    @Test
    void leaveTypesAreListedByIdWithoutTheDeletedOnes() {
        publish(Events.TYPE_CREATED, 3, 1, Events.type(3, "Sick", "Doctor's note", false, true));
        publish(Events.TYPE_CREATED, 1, 1, Events.type(1, "Vacation"));
        publish(Events.TYPE_CREATED, 2, 1, Events.type(2, "Unpaid", null, true, false));
        publish(Events.TYPE_DELETED, 2, 2, Events.tombstone("leaveType", 2));
        drain();

        JsonNode types = getJson("/v1/leavetypes");

        assertThat(textsOf(types, "leaveTypeName")).containsExactly("Vacation", "Sick");
        JsonNode sick = types.get(1);
        assertThat(sick.path("leaveTypeId").asLong()).isEqualTo(3);
        assertThat(sick.path("leaveTypeDescription").asText()).isEqualTo("Doctor's note");
        assertThat(sick.path("requiresApproval").asBoolean(true)).isFalse();
        assertThat(sick.path("isPaidLeave").asBoolean()).isTrue();
        assertThat(types.get(0).path("leaveTypeDescription").isNull()).isTrue();
        assertThat(getJson("/v1/leavetypes/3").path("leaveTypeName").asText()).isEqualTo("Sick");
        assertProblem(exchange(org.springframework.http.HttpMethod.GET, "/v1/leavetypes/2"), HttpStatus.NOT_FOUND);
        assertProblem(exchange(org.springframework.http.HttpMethod.GET, "/v1/leavetypes/404"), HttpStatus.NOT_FOUND);
    }

    // ---- leave requests ----

    @Test
    void leaveRequestsAreListedByIdWithTheContractFieldsOnly() {
        publish(Events.SUBMITTED, 12, 1, Events.request(12, 10, 1, "Vacation", "2027-05-03", "2027-05-04", "PENDING"));
        publish(Events.REJECTED, 11, 2, Events.decided(
                Events.request(11, 10, 1, "Vacation", "2027-04-01", "2027-04-02", "REJECTED"), 5L, 99, "REJECTED", "no"));
        drain();

        JsonNode requests = getJson("/v1/leaverequests");

        assertThat(requests.findValuesAsText("leaveRequestId")).containsExactly("11", "12");
        JsonNode rejected = requests.get(0);
        assertThat(fieldNames(rejected)).containsExactlyInAnyOrder("leaveRequestId", "employeeId", "leaveTypeId",
                "startDate", "endDate", "requestStatus", "reason", "rejectionReason", "requestedDatetime");
        assertThat(rejected.path("requestStatus").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("startDate").asText()).isEqualTo("2027-04-01");
        assertThat(rejected.path("reason").asText()).isEqualTo("family");
        assertThat(rejected.path("requestedDatetime").asText()).isEqualTo("2027-01-01T10:00:00.123456Z");
        assertThat(getJson("/v1/leaverequests/12").path("requestStatus").asText()).isEqualTo("PENDING");
        assertProblem(exchange(org.springframework.http.HttpMethod.GET, "/v1/leaverequests/13"), HttpStatus.NOT_FOUND);
    }

    // ---- leave approvals ----

    @Test
    void approvalsAreDerivedFromTheDecisionsOfTheRequests() {
        publish(Events.SUBMITTED, 20, 1, Events.request(20, 10, 1, "Vacation", "2027-05-03", "2027-05-04", "PENDING"));
        publish(Events.REJECTED, 21, 2, Events.decided(
                Events.request(21, 10, 1, "Vacation", "2027-06-01", "2027-06-02", "REJECTED"), 8L, 99, "REJECTED", "no"));
        publish(Events.APPROVED, 22, 3, Events.decided(
                Events.request(22, 11, 1, "Vacation", "2027-07-01", "2027-07-02", "APPROVED"), 4L, 98, "APPROVED", "enjoy"));
        publish(Events.REJECTED, 23, 2, Events.decided(
                Events.request(23, 12, 1, "Vacation", "2027-08-01", "2027-08-02", "REJECTED"), null, 97, "REJECTED", null));
        drain();

        JsonNode approvals = getJson("/v1/leaveapprovals");

        assertThat(approvals).hasSize(3);
        assertThat(approvals.findValuesAsText("leaveRequestId")).as("by approval id, unknown ids last")
                .containsExactly("22", "21", "23");
        JsonNode approved = approvals.get(0);
        assertThat(fieldNames(approved)).containsExactlyInAnyOrder("leaveApprovalId", "leaveRequestId",
                "approverEmployeeId", "decision", "leaveComment", "decisionDatetime");
        assertThat(approved.path("leaveApprovalId").asLong()).isEqualTo(4);
        assertThat(approved.path("approverEmployeeId").asLong()).isEqualTo(98);
        assertThat(approved.path("decision").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("leaveComment").asText()).isEqualTo("enjoy");
        assertThat(approved.path("decisionDatetime").asText()).isEqualTo("2027-01-02T09:30:00.500Z");
        assertThat(approvals.get(2).path("leaveApprovalId").isNull()).as("a decision from before the id existed")
                .isTrue();

        assertThat(getJson("/v1/leaveapprovals/8").path("leaveRequestId").asLong()).isEqualTo(21);
        assertProblem(exchange(org.springframework.http.HttpMethod.GET, "/v1/leaveapprovals/9"), HttpStatus.NOT_FOUND);
        assertThat(getJson("/v1/leaveapprovals/request/22")).hasSize(1);
        assertThat(getJson("/v1/leaveapprovals/request/22").get(0).path("leaveApprovalId").asLong()).isEqualTo(4);
        assertThat(getJson("/v1/leaveapprovals/request/20")).as("pending: no decision yet").isEmpty();
        assertThat(getJson("/v1/leaveapprovals/request/999")).as("unknown request: empty, not 404").isEmpty();
    }

    // ---- ledger ----

    @Test
    void ledgerEntriesAreListedById() {
        publish(Events.LEDGER, 3, 1, Events.ledger(3, 10, 1, "-2.00", "8.00", "RESERVATION"));
        publish(Events.LEDGER, 1, 1, Events.ledger(1, 10, 1, "10", "10", "ACCRUAL"));
        drain();

        JsonNode entries = getJson("/v1/leaveledgers");

        assertThat(entries.findValuesAsText("leaveLedgerId")).containsExactly("1", "3");
        JsonNode reservation = entries.get(1);
        assertThat(fieldNames(reservation)).containsExactlyInAnyOrder("leaveLedgerId", "employeeId", "leaveTypeId",
                "changeAmountDays", "transactionType", "referenceEntityType", "referenceEntityId",
                "transactionDatetime");
        assertThat(reservation.path("changeAmountDays").decimalValue()).isEqualByComparingTo("-2");
        assertThat(reservation.path("transactionType").asText()).isEqualTo("RESERVATION");
        assertThat(getJson("/v1/leaveledgers/1").path("transactionType").asText()).isEqualTo("ACCRUAL");
        assertProblem(exchange(org.springframework.http.HttpMethod.GET, "/v1/leaveledgers/2"), HttpStatus.NOT_FOUND);
    }

    @Test
    void ledgerAmountsKeepTheDecimalsTheyWerePublishedWith() {
        publish(Events.LEDGER, 803, 1, Events.ledger(803, 10, 1, "10.00", "10.00", "ACCRUAL"));
        ProjectionEnvelope last = publish(Events.LEDGER, 804, 1, Events.ledger(804, 10, 1, "-2.50", "7.50", "ADJUSTMENT"));
        awaitProcessed(last);
        drain();

        assertThat(http.getForObject("/v1/leaveledgers/803", String.class)).contains("\"changeAmountDays\":10.00");
        assertThat(http.getForObject("/v1/leaveledgers/804", String.class)).contains("\"changeAmountDays\":-2.50");
        assertThat(http.getForObject("/v1/leaveledgers", String.class))
                .contains("\"changeAmountDays\":10.00").contains("\"changeAmountDays\":-2.50");
        org.bson.Document stored = mongo.findById(803L, org.bson.Document.class, "ledger_entries");
        assertThat(((org.bson.types.Decimal128) stored.get("changeAmountDays")).toString()).isEqualTo("10.00");
        assertThat(((org.bson.types.Decimal128) stored.get("balanceAfterDays")).toString()).isEqualTo("10.00");
    }

    // ---- views ----

    private void seedOverview() {
        publish(Events.TYPE_CREATED, 1, 1, Events.type(1, "Vacation"));
        publish(Events.TYPE_CREATED, 2, 1, Events.type(2, "Sick leave"));
        publish(Events.EMPLOYEE_CREATED, 10, 1, Events.employee(10, "Grace", "Hopper"));
        publish(Events.EMPLOYEE_CREATED, 11, 1, Events.employee(11, "Ada", "Lovelace"));
        publish(Events.EMPLOYEE_CREATED, 12, 1, Events.employee(12, "Gone", "Employee"));
        publish(Events.EMPLOYEE_DELETED, 12, 2, Events.tombstone("employee", 12));
        publish(Events.LEDGER, 1, 1, Events.ledger(1, 10, 1, "10.00", "10.00", "ACCRUAL"));
        publish(Events.LEDGER, 2, 1, Events.ledger(2, 10, 1, "-3.00", "7.00", "RESERVATION"));
        publish(Events.LEDGER, 3, 1, Events.ledger(3, 10, 2, "5", "5", "ACCRUAL"));
        publish(Events.LEDGER, 4, 1, Events.ledger(4, 11, 1, "1.255", "1.255", "ADJUSTMENT"));
        // employee 10: 3 days approved vacation and a later sick leave; employee 11 and 12; employee 13 is unknown
        publish(Events.APPROVED, 3, 3, Events.request(3, 10, 1, "Vacation", "2027-03-01", "2027-03-03", "APPROVED"));
        publish(Events.SUBMITTED, 2, 1, Events.request(2, 10, 2, "Sick leave", "2027-02-01", "2027-02-02", "PENDING"));
        publish(Events.SUBMITTED, 1, 1, Events.request(1, 11, 1, "Vacation", "2027-01-05", "2027-01-06", "PENDING"));
        publish(Events.SUBMITTED, 4, 1, Events.request(4, 12, 1, "Vacation", "2027-04-01", "2027-04-02", "PENDING"));
        publish(Events.CANCELLED, 5, 2, Events.request(5, 13, 1, "Vacation", "2027-05-01", "2027-05-02", "CANCELLED"));
        drain();
    }

    @Test
    void theOverviewHasOneRowPerRequestSortedWithNamesAndBalancesAndWithoutDeletedEmployees() {
        seedOverview();

        JsonNode rows = getJson("/v1/views/employee-leave-overview");

        assertThat(rows.findValuesAsText("leaveRequestId")).as("employee, then start date, then request")
                .containsExactly("2", "3", "1", "5");
        JsonNode first = rows.get(0);
        assertThat(fieldNames(first)).containsExactlyInAnyOrder("employeeId", "employeeName", "leaveRequestId",
                "leaveTypeName", "startDate", "endDate", "requestStatus", "balanceDays");
        assertThat(first.path("employeeName").asText()).isEqualTo("Grace Hopper");
        assertThat(first.path("leaveTypeName").asText()).isEqualTo("Sick leave");
        assertThat(first.path("balanceDays").decimalValue()).isEqualByComparingTo("5");
        assertThat(rows.get(1).path("balanceDays").decimalValue()).as("10 accrued - 3 reserved")
                .isEqualByComparingTo("7");
        assertThat(rows.get(2).path("employeeName").asText()).isEqualTo("Ada Lovelace");
        assertThat(rows.get(2).path("balanceDays").decimalValue()).as("rounded to two decimals")
                .isEqualByComparingTo("1.26");
        assertThat(rows.get(3).path("employeeName").isNull()).as("employee not known yet").isTrue();
        assertThat(rows.get(3).path("balanceDays").decimalValue()).isEqualByComparingTo("0");
        assertThat(rows.findValuesAsText("employeeId")).doesNotContain("12");
        assertThat(getJson("/v1/leaverequests").findValuesAsText("leaveRequestId"))
                .as("the deleted employee's request stays in /v1/leaverequests").contains("4");
    }

    @Test
    void balancesAreShownWithTwoDecimalsInTheJson() throws Exception {
        seedOverview();

        String body = http.getForObject("/v1/views/employee-leave-overview/employee/10", String.class);

        assertThat(body).contains("\"balanceDays\":5.00").contains("\"balanceDays\":7.00");
    }

    @Test
    void theOverviewCanBeFilteredByEmployee() {
        seedOverview();

        assertThat(getJson("/v1/views/employee-leave-overview/employee/10").findValuesAsText("leaveRequestId"))
                .containsExactly("2", "3");
        assertThat(getJson("/v1/views/employee-leave-overview/employee/11").findValuesAsText("leaveRequestId"))
                .containsExactly("1");
        assertThat(getJson("/v1/views/employee-leave-overview/employee/12")).as("deleted employee").isEmpty();
        assertThat(getJson("/v1/views/employee-leave-overview/employee/999")).isEmpty();
    }

    @Test
    void theOverviewCanBeFilteredByStatusIgnoringCase() {
        seedOverview();

        assertThat(getJson("/v1/views/employee-leave-overview/status/PENDING").findValuesAsText("leaveRequestId"))
                .containsExactly("2", "1");
        assertThat(getJson("/v1/views/employee-leave-overview/status/approved").findValuesAsText("leaveRequestId"))
                .containsExactly("3");
        assertThat(getJson("/v1/views/employee-leave-overview/status/Cancelled").findValuesAsText("leaveRequestId"))
                .containsExactly("5");
        assertThat(getJson("/v1/views/employee-leave-overview/status/REJECTED")).isEmpty();
        assertThat(getJson("/v1/views/employee-leave-overview/status/approval_pending")).isEmpty();
    }

    @Test
    void anUnknownStatusIsABadRequest() {
        ResponseEntity<JsonNode> response = exchange(org.springframework.http.HttpMethod.GET,
                "/v1/views/employee-leave-overview/status/DONE");

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().path("detail").asText()).contains("DONE").contains("APPROVED");
    }

    @Test
    void theOverviewCanBeFilteredByLeaveTypeNameIgnoringCase() {
        seedOverview();

        assertThat(getJson("/v1/views/employee-leave-overview/leave-type/vacation").findValuesAsText("leaveRequestId"))
                .containsExactly("3", "1", "5");
        assertThat(getJson("/v1/views/employee-leave-overview/leave-type/SICK LEAVE")
                .findValuesAsText("leaveRequestId")).containsExactly("2");
        assertThat(getJson("/v1/views/employee-leave-overview/leave-type/Parental")).isEmpty();
    }

    // ---- errors ----

    @Test
    void aNonNumericIdIsABadRequestNotAServerError() {
        for (String path : List.of("/v1/leavetypes/abc", "/v1/leaverequests/abc", "/v1/leaveapprovals/abc",
                "/v1/leaveapprovals/request/abc", "/v1/leaveledgers/abc",
                "/v1/views/employee-leave-overview/employee/abc", "/v1/leavetypes/1.5")) {
            assertProblem(exchange(org.springframework.http.HttpMethod.GET, path), HttpStatus.BAD_REQUEST);
        }
    }

    @Test
    void anUnknownRouteIsAProblem404() {
        assertProblem(exchange(org.springframework.http.HttpMethod.GET, "/v1/nothing"), HttpStatus.NOT_FOUND);
        assertProblem(exchange(org.springframework.http.HttpMethod.GET, "/v1/views/employee-leave-overview/nope/1"),
                HttpStatus.NOT_FOUND);
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, HttpStatus status) {
        assertThat(response.getStatusCode()).as(String.valueOf(response.getBody())).isEqualTo(status);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(response.getBody().path("status").asInt()).isEqualTo(status.value());
        assertThat(response.getBody().path("title").asText()).isNotBlank();
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
