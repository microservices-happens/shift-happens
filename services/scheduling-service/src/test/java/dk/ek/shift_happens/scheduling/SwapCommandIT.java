package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftAssignmentRequest;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftRequest;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftSwapApprovalRequest;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftSwapRequest;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftSwapApprovalRequest;
import dk.ek.shift_happens.scheduling.api.dto.RequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftAssignmentResponse;
import dk.ek.shift_happens.scheduling.api.dto.ShiftResponse;
import dk.ek.shift_happens.scheduling.api.dto.ShiftSwapApprovalResponse;
import dk.ek.shift_happens.scheduling.api.dto.ShiftSwapResponse;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Swaps (P2.9). The colleague is validated with the same policy as a direct assignment, and an
 * approval moves the assignment in one transaction: assignment.updated.v1 and swap.approved.v1 come
 * from the same commit.
 */
class SwapCommandIT extends AbstractCommandIT {

    private static final String SWAP_REQUESTED = "scheduling.swap.requested.v1";
    private static final String SWAP_APPROVED = "scheduling.swap.approved.v1";
    private static final String SWAP_REJECTED = "scheduling.swap.rejected.v1";
    private static final String ASSIGNMENT_UPDATED = "scheduling.assignment.updated.v1";
    private static final Instant START = Instant.parse("2027-02-15T06:00:00Z");
    private static final Instant END = Instant.parse("2027-02-15T14:00:00Z");

    @Test
    void anEmployeeCanAskAColleagueToTakeTheShift() {
        Long assignmentId = assignedShift(401, 402);

        ResponseEntity<ShiftSwapResponse> response = post("/v1/shiftswaps",
                new CreateShiftSwapRequest(assignmentId, 401, 402, "holiday"), ShiftSwapResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        Long shiftSwapId = response.getBody().shiftSwapId();
        assertThat(response.getBody().swapStatus()).isEqualTo("PENDING");
        assertThat(response.getBody().decision()).isNull();

        JsonNode event = receiveEvent(SWAP_REQUESTED, shiftSwapId);
        JsonNode data = event.path("data");
        assertThat(data.path("originalShiftAssignmentId").asLong()).isEqualTo(assignmentId);
        assertThat(data.path("employeeFromId").asLong()).isEqualTo(401);
        assertThat(data.path("employeeToId").asLong()).isEqualTo(402);
        assertThat(data.path("swapStatus").asText()).isEqualTo("PENDING");
        assertThat(data.path("decision").isNull()).isTrue();
    }

    @Test
    void anEmployeeCannotSwapSomebodyElsesAssignment() {
        Long assignmentId = assignedShift(403, 404);

        ResponseEntity<String> response = post("/v1/shiftswaps",
                new CreateShiftSwapRequest(assignmentId, 999, 404, "holiday"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("belongs to employee 403, not 999");
    }

    @Test
    void anEmployeeTokenMayRequestASwapForTheirOwnAssignmentOnly() {
        Long own = assignedShift(431, 432);
        Long somebodyElses = assignedShift(433, 434);

        ResponseEntity<ShiftSwapResponse> allowed = postWith(TestJwks.bearer(TestJwks.employee(431)),
                "/v1/shiftswaps", new CreateShiftSwapRequest(own, 431, 432, "holiday"), ShiftSwapResponse.class);
        // employeeFromId (433) is not the employeeId of the token (431).
        ResponseEntity<String> refused = postWith(TestJwks.bearer(TestJwks.employee(431)),
                "/v1/shiftswaps", new CreateShiftSwapRequest(somebodyElses, 433, 434, "holiday"), String.class);

        assertThat(allowed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(refused.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_swap WHERE original_shift_assignment_id = ?",
                Integer.class, somebodyElses)).isZero();
    }

    @Test
    void anEmployeeTokenMayNotDecideASwap() {
        Long assignmentId = assignedShift(435, 436);
        Long shiftSwapId = requestSwap(assignmentId, 435, 436);

        ResponseEntity<String> response = postWith(TestJwks.bearer(TestJwks.employee(436)),
                "/v1/shiftswapapprovals", new CreateShiftSwapApprovalRequest(shiftSwapId, 436, "APPROVED", "mine"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT swap_status FROM shift_swap WHERE shift_swap_id = ?", String.class,
                shiftSwapId)).isEqualTo("PENDING");
    }

    @Test
    void aColleagueWithoutTheJobRoleIsRejected() {
        Long assignmentId = assignedShift(405, 0);
        addEmployee(406, "ACTIVE", List.of(99L));

        ResponseEntity<String> response = post("/v1/shiftswaps",
                new CreateShiftSwapRequest(assignmentId, 405, 406, "holiday"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("holds none of the job roles");
    }

    @Test
    void aSecondPendingSwapForTheSameAssignmentIsAConflict() {
        Long assignmentId = assignedShift(407, 408);
        post("/v1/shiftswaps", new CreateShiftSwapRequest(assignmentId, 407, 408, "holiday"),
                ShiftSwapResponse.class);

        ResponseEntity<String> response = post("/v1/shiftswaps",
                new CreateShiftSwapRequest(assignmentId, 407, 408, "again"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("already has a pending swap");
    }

    @Test
    void approvingASwapMovesTheAssignmentInTheSameTransaction() {
        Long assignmentId = assignedShift(409, 410);
        Long shiftSwapId = requestSwap(assignmentId, 409, 410);

        ResponseEntity<ShiftSwapApprovalResponse> response = approve(shiftSwapId, "APPROVED", "ok");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().decision()).isEqualTo("APPROVED");

        JsonNode assignment = receiveEvent(ASSIGNMENT_UPDATED, assignmentId);
        assertThat(assignment.path("data").path("employeeId").asLong()).isEqualTo(410);
        assertThat(assignment.path("data").path("assignmentStatus").asText()).isEqualTo("SWAPPED");

        JsonNode swap = receiveEvent(SWAP_APPROVED, shiftSwapId);
        assertThat(swap.path("data").path("swapStatus").asText()).isEqualTo("APPROVED");
        assertThat(swap.path("data").path("decision").path("decision").asText()).isEqualTo("APPROVED");

        assertThat(jdbc.queryForObject(
                "SELECT employee_id FROM shift_assignment WHERE shift_assignment_id = ?", Long.class, assignmentId))
                .isEqualTo(410L);
        assertThat(jdbc.queryForObject(
                "SELECT assignment_status FROM shift_assignment WHERE shift_assignment_id = ?", String.class,
                assignmentId)).isEqualTo("SWAPPED");

        ResponseEntity<String> second = approveRaw(shiftSwapId, "REJECTED", "late");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("is already APPROVED");
    }

    @Test
    void rejectingLeavesTheAssignmentWithTheOriginalEmployee() {
        Long assignmentId = assignedShift(411, 412);
        Long shiftSwapId = requestSwap(assignmentId, 411, 412);

        assertThat(approveRaw(shiftSwapId, "REJECTED", "no").getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(receiveEvent(SWAP_REJECTED, shiftSwapId).path("data").path("swapStatus").asText())
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT employee_id FROM shift_assignment WHERE shift_assignment_id = ?", Long.class, assignmentId))
                .isEqualTo(411L);
        assertThat(jdbc.queryForObject(
                "SELECT assignment_status FROM shift_assignment WHERE shift_assignment_id = ?", String.class,
                assignmentId)).isEqualTo("ASSIGNED");
    }

    @Test
    void aPendingSwapCanBeWithdrawn() {
        Long assignmentId = assignedShift(413, 414);
        Long shiftSwapId = requestSwap(assignmentId, 413, 414);

        assertThat(deleteRaw("/v1/shiftswaps/" + shiftSwapId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shift_swap WHERE shift_swap_id = ?", Integer.class,
                shiftSwapId)).isZero();
        assertThat(approveRaw(shiftSwapId, "APPROVED", "too late").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aDecisionCanBeCorrectedButNotFlipped() {
        Long assignmentId = assignedShift(415, 416);
        Long shiftSwapId = requestSwap(assignmentId, 415, 416);
        ShiftSwapApprovalResponse approval = approve(shiftSwapId, "APPROVED", "ok").getBody();
        receiveEvent(SWAP_APPROVED, shiftSwapId);

        ResponseEntity<ShiftSwapApprovalResponse> corrected = put(
                "/v1/shiftswapapprovals/" + approval.shiftSwapApprovalId(),
                new UpdateShiftSwapApprovalRequest(shiftSwapId, 2, "APPROVED", "decided by the deputy"),
                ShiftSwapApprovalResponse.class);

        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(corrected.getBody()).isNotNull();
        assertThat(corrected.getBody().approverEmployeeId()).isEqualTo(2);
        assertThat(corrected.getBody().shiftSwapComment()).isEqualTo("decided by the deputy");

        JsonNode correctedEvent = receiveEvent(SWAP_APPROVED, shiftSwapId);
        assertThat(correctedEvent.path("aggregateVersion").asLong()).isEqualTo(3);
        assertThat(correctedEvent.path("data").path("swapStatus").asText()).isEqualTo("APPROVED");
        assertThat(correctedEvent.path("data").path("decision").path("comment").asText())
                .isEqualTo("decided by the deputy");

        ResponseEntity<String> flipped = put("/v1/shiftswapapprovals/" + approval.shiftSwapApprovalId(),
                new UpdateShiftSwapApprovalRequest(shiftSwapId, 2, "REJECTED", "changed my mind"), String.class);
        assertThat(flipped.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(flipped.getBody()).contains("cannot be reversed");
    }

    @Test
    void aDecisionRecordCanBeDeletedWithoutUndoingTheHandover() {
        Long assignmentId = assignedShift(417, 418);
        Long shiftSwapId = requestSwap(assignmentId, 417, 418);
        ShiftSwapApprovalResponse approval = approve(shiftSwapId, "APPROVED", "ok").getBody();

        assertThat(deleteRaw("/v1/shiftswapapprovals/" + approval.shiftSwapApprovalId()).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shift_swap_approval WHERE shift_swap_approval_id = ?", Integer.class,
                approval.shiftSwapApprovalId())).isZero();
        // The handover stands: the swap is still approved and the assignment belongs to the colleague.
        assertThat(jdbc.queryForObject("SELECT swap_status FROM shift_swap WHERE shift_swap_id = ?",
                String.class, shiftSwapId)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "SELECT employee_id FROM shift_assignment WHERE shift_assignment_id = ?", Long.class,
                assignmentId)).isEqualTo(418L);
        assertThat(deleteRaw("/v1/shiftswapapprovals/" + approval.shiftSwapApprovalId()).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void correctingAnUnknownApprovalIsNotFound() {
        ResponseEntity<String> response = put("/v1/shiftswapapprovals/999999",
                new UpdateShiftSwapApprovalRequest(1, 1, "APPROVED", "x"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCorrectionMustNameTheSwapItBelongsTo() {
        Long assignmentId = assignedShift(419, 420);
        Long shiftSwapId = requestSwap(assignmentId, 419, 420);
        ShiftSwapApprovalResponse approval = approve(shiftSwapId, "APPROVED", "ok").getBody();

        ResponseEntity<String> response = put("/v1/shiftswapapprovals/" + approval.shiftSwapApprovalId(),
                new UpdateShiftSwapApprovalRequest(shiftSwapId + 5000, 2, "APPROVED", "wrong swap"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("belongs to swap " + shiftSwapId);
    }


    /** Creates a shift, assigns it to the first employee and (when non-zero) registers the colleague. */
    private Long assignedShift(long employeeId, long colleagueId) {
        Long shiftId = post("/v1/shifts", new CreateShiftRequest(2, 3, "Shift " + employeeId, START, END,
                "PLANNED", List.of(new RequiredJobRoleRequest(7, 1))), ShiftResponse.class).getBody().shiftId();
        addEmployee(employeeId, "ACTIVE", List.of(7L));
        if (colleagueId != 0) {
            addEmployee(colleagueId, "ACTIVE", List.of(7L));
        }
        return post("/v1/shiftassignments",
                new CreateShiftAssignmentRequest(shiftId, employeeId, "ASSIGNED"), ShiftAssignmentResponse.class)
                .getBody().shiftAssignmentId();
    }

    private Long requestSwap(Long assignmentId, long from, long to) {
        return post("/v1/shiftswaps", new CreateShiftSwapRequest(assignmentId, from, to, "holiday"),
                ShiftSwapResponse.class).getBody().shiftSwapId();
    }

    private ResponseEntity<ShiftSwapApprovalResponse> approve(Long shiftSwapId, String decision, String comment) {
        return post("/v1/shiftswapapprovals",
                new CreateShiftSwapApprovalRequest(shiftSwapId, 1, decision, comment),
                ShiftSwapApprovalResponse.class);
    }

    private ResponseEntity<String> approveRaw(Long shiftSwapId, String decision, String comment) {
        return post("/v1/shiftswapapprovals",
                new CreateShiftSwapApprovalRequest(shiftSwapId, 1, decision, comment), String.class);
    }

    private void addEmployee(long employeeId, String status, List<Long> jobRoleIds) {
        publish(new EventEnvelope(UUID.randomUUID(), "workforce.employee.created.v1", Instant.now(),
                "workforce-service", employeeId, 1, UUID.randomUUID().toString(), null, Map.of(
                        "employeeId", employeeId,
                        "employeeNumber", "EMP-" + employeeId,
                        "userRole", "Employee",
                        "firstName", "Ada",
                        "lastName", "Lovelace",
                        "email", "ada" + employeeId + "@shift.dk",
                        "employmentStatus", status,
                        "jobRoleIds", jobRoleIds)));
        awaitCount("SELECT count(*) FROM employee_replica WHERE employee_id = ?", 1, employeeId);
    }
}
