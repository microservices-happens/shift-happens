package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.scheduling.application.Viewer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The GraphQL API (P2.11) with a real RS256 token: nested shifts and assignments for a Manager, and the
 * visibility rules for an Employee, plus the 401s of the security baseline.
 */
class SchedulingGraphQlIT extends AbstractQueryIT {

    private static final String FROM = "2027-04-05T00:00:00Z";
    private static final String TO = "2027-04-12T00:00:00Z";
    private static final Instant START = Instant.parse("2027-04-05T06:00:00Z");
    private static final Instant END = Instant.parse("2027-04-05T14:00:00Z");

    private static final String SHIFTS_QUERY = """
            { shifts(from: "%s", to: "%s") {
                shiftId
                shiftName
                status
                isUnderstaffed
                requiredJobRoles { jobRoleId requiredEmployeeCount assignedCount }
                assignments { shiftAssignmentId status employee { employeeId firstName lastName } }
              } }
            """.formatted(FROM, TO);

    @Test
    void aManagerSeesNestedShiftsWithAssignmentsAndEmployees() {
        long shiftId = 8001;
        seedShift(shiftId, "Weekday early", 201, "Ada");

        JsonNode shift = shiftsFor(TestJwks.token(900, "Manager")).get(0);

        assertThat(shift.path("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(shift.path("shiftName").asText()).isEqualTo("Weekday early");
        assertThat(shift.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(shift.path("requiredJobRoles").get(0).path("assignedCount").asInt()).isEqualTo(1);
        assertThat(shift.path("isUnderstaffed").asBoolean()).isTrue();
        assertThat(shift.path("assignments").get(0).path("employee").path("firstName").asText())
                .isEqualTo("Ada");
    }

    @Test
    void anEmployeeOnlySeesTheirOwnShiftsAndSwaps() {
        long mine = 8002;
        long somebodyElse = 8003;
        seedShift(mine, "Mine", 202, "Bo");
        seedShift(somebodyElse, "Not mine", 203, "Cy");
        publish("scheduling.swap.requested.v1", 61, 1, "scheduling-service",
                swapPayload(61, mine * 10 + 1, mine, 202, 203));
        publish("scheduling.swap.requested.v1", 62, 1, "scheduling-service",
                swapPayload(62, somebodyElse * 10 + 1, somebodyElse, 203, 204));

        String employeeToken = TestJwks.token(202, "Employee");

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.swaps(new Viewer(202L, "Employee"), null)).hasSize(1));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.swaps(new Viewer(900L, "Manager"), null)).hasSize(2));

        JsonNode shifts = shiftsFor(employeeToken);
        assertThat(shifts).hasSize(1);
        assertThat(shifts.get(0).path("shiftId").asLong()).isEqualTo(mine);
        assertThat(shifts.get(0).path("assignments")).hasSize(1);

        JsonNode swapResponse = graphQl("""
                { swapRequests { shiftSwapId status
                                 from { employeeId } to { employeeId }
                                 assignment { shiftAssignmentId } } }
                """, employeeToken);
        assertThat(swapResponse.path("errors")).as("GraphQL errors: %s", swapResponse).isEmpty();
        JsonNode swaps = swapResponse.path("data").path("swapRequests");
        assertThat(swaps).hasSize(1);
        assertThat(swaps.get(0).path("shiftSwapId").asLong()).isEqualTo(61);
        assertThat(swaps.get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(swaps.get(0).path("from").path("employeeId").asLong()).isEqualTo(202);
        assertThat(swaps.get(0).path("to").path("employeeId").asLong()).isEqualTo(203);
        assertThat(swaps.get(0).path("assignment").path("shiftAssignmentId").asLong())
                .isEqualTo(mine * 10 + 1);

        // A manager sees both shifts and both swaps.
        assertThat(shiftsFor(TestJwks.token(900, "Manager"))).hasSize(2);
        assertThat(graphQl("{ swapRequests { shiftSwapId } }", TestJwks.token(900, "Administrator"))
                .path("data").path("swapRequests")).hasSize(2);
    }
    @Test
    void anEmployeeCannotReadSomebodyElsesSchedule() {
        long shiftId = 8004;
        seedShift(shiftId, "Theirs", 205, "Di");

        JsonNode response = graphQl("""
                { employeeSchedule(employeeId: 205, from: "%s", to: "%s") { shiftAssignmentId } }
                """.formatted(FROM, TO), TestJwks.token(206, "Employee"));

        assertThat(response.path("errors")).hasSize(1);
        assertThat(response.path("errors").get(0).path("message").asText())
                .contains("only read their own schedule");
        assertThat(response.path("errors").get(0).path("extensions").path("classification").asText())
                .isEqualTo("FORBIDDEN");

        // The same query with a manager token returns the schedule.
        assertThat(graphQl("""
                { employeeSchedule(employeeId: 205, from: "%s", to: "%s") { shiftAssignmentId } }
                """.formatted(FROM, TO), TestJwks.token(900, "Manager"))
                .path("data").path("employeeSchedule")).hasSize(1);
    }

    @Test
    void aMissingTokenIsRejected() {
        ResponseEntity<String> response = http.postForEntity("/graphql",
                new HttpEntity<>(Map.of("query", "{ shifts(from: \"" + FROM + "\", to: \"" + TO + "\") { shiftId } }"),
                        json()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aTokenSignedByAnotherKeyIsRejected() {
        ResponseEntity<String> response = http.postForEntity("/graphql",
                new HttpEntity<>(Map.of("query", "{ swapRequests { shiftSwapId } }"),
                        authorized(TestJwks.tokenSignedByAnotherKey(900, "Manager"))), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void graphiqlIsNotServedOutsideTheDevProfile() {
        assertThat(http.exchange("/graphiql", org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class).getStatusCode())
                .isNotEqualTo(HttpStatus.OK);
    }

    private JsonNode shiftsFor(String token) {
        ResponseEntity<String> response = http.postForEntity("/graphql",
                new HttpEntity<>(Map.of("query", SHIFTS_QUERY), authorized(token)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return graphQlNodes(response).path("data").path("shifts");
    }

    private JsonNode graphQl(String query, String token) {
        ResponseEntity<String> response = http.postForEntity("/graphql",
                new HttpEntity<>(Map.of("query", query), authorized(token)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return graphQlNodes(response);
    }

    private JsonNode graphQlNodes(ResponseEntity<String> response) {
        try {
            return objectMapper.readTree(response.getBody());
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            throw new IllegalStateException("the GraphQL response is not JSON: " + response.getBody(), notJson);
        }
    }

    private HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Creates one published shift with one assignment, one employee and a suggestion. */
    private void seedShift(long shiftId, String name, long employeeId, String firstName) {
        publish("scheduling.shift.created.v1", shiftId, 1, "scheduling-service", Map.of(
                "shiftId", shiftId,
                "departmentId", 2,
                "workLocationId", 3,
                "shiftName", name,
                "startDatetime", START.toString(),
                "endDatetime", END.toString(),
                "shiftStatus", "PUBLISHED",
                "requiredJobRoles", List.of(Map.of("jobRoleId", 7, "requiredEmployeeCount", 2))));
        publish("workforce.employee.created.v1", employeeId, 1, "workforce-service", Map.of(
                "employeeId", employeeId,
                "employeeNumber", "EMP-" + employeeId,
                "userRole", "Employee",
                "firstName", firstName,
                "lastName", "Lovelace",
                "email", "emp" + employeeId + "@shift.dk",
                "employmentStatus", "ACTIVE",
                "jobRoleIds", List.of(7),
                "weeklyContractHours", 37.0));
        long assignmentId = shiftId * 10 + 1;
        publish("scheduling.assignment.created.v1", assignmentId, 1, "scheduling-service", Map.of(
                "shiftAssignmentId", assignmentId,
                "shiftId", shiftId,
                "employeeId", employeeId,
                "assignmentStatus", "ASSIGNED",
                "shiftStartDatetime", START.toString(),
                "shiftEndDatetime", END.toString()));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.assignmentsOfShift(shiftId)).hasSize(1));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(queries.employee(employeeId)).isNotNull());
    }

    private static Map<String, Object> swapPayload(long swapId, long assignmentId, long shiftId, long from,
                                                   long to) {
        return Map.of(
                "shiftSwapId", swapId,
                "originalShiftAssignmentId", assignmentId,
                "shiftId", shiftId,
                "employeeFromId", from,
                "employeeToId", to,
                "swapStatus", "PENDING",
                "reason", "holiday",
                "requestDatetime", Instant.now().toString());
    }
}
