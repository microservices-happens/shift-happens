package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftAssignmentRequest;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftRequest;
import dk.ek.shift_happens.scheduling.api.dto.RequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftAssignmentResponse;
import dk.ek.shift_happens.scheduling.api.dto.ShiftResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftAssignmentRequest;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * P2.12: a cancelled or released assignment leaves a shift short, so Scheduling publishes the shortage
 * with candidates that ai-service can rank without asking anybody anything else.
 *
 * <p>Workforce and Leave are not implemented yet, so the employee and leave events are published here
 * exactly as the contracts describe them.
 */
class UnderstaffedIT extends AbstractCommandIT {

    private static final String UNDERSTAFFED = "scheduling.shift.understaffed.v1";
    private static final String LEAVE_APPROVED = "leave.request.approved.v1";
    private static final String LEAVE_CANCELLED = "leave.request.cancelled.v1";

    @Test
    void aCancellationPublishesTheShortageWithTheCandidates() {
        Instant start = Instant.now().plusSeconds(5 * 24 * 3600);
        Long shiftId = createShift(start, 1);
        addEmployee(601, "ACTIVE", List.of(7L), 37.5);
        addEmployee(602, "ACTIVE", List.of(7L), 20.0);
        addEmployee(603, "ACTIVE", List.of(7L), 37.5);
        Long assignmentId = assign(shiftId, 601);

        // 603 is away, so they must not be offered.
        LocalDate date = LocalDate.ofInstant(start, ZoneOffset.UTC);
        publish(leaveEvent(LEAVE_APPROVED, 700, 603, date, 1, "APPROVED"));
        awaitCount("SELECT count(*) FROM leave_window WHERE leave_request_id = ?", 1, 700L);

        assertThat(put("/v1/shiftassignments/" + assignmentId,
                new UpdateShiftAssignmentRequest(shiftId, 601, "CANCELLED", null, null), String.class)
                .getStatusCode().value()).isEqualTo(202);

        JsonNode data = receiveEvent(UNDERSTAFFED, shiftId).path("data");
        assertThat(data.path("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(data.path("jobRoleId").asLong()).isEqualTo(7);
        assertThat(data.path("missingCount").asInt()).isEqualTo(1);
        assertThat(data.path("startDatetime").asText()).isNotBlank();

        JsonNode candidates = data.path("candidates");
        List<Long> candidateIds = new java.util.ArrayList<>();
        candidates.forEach(candidate -> candidateIds.add(candidate.path("employeeId").asLong()));
        assertThat(candidateIds).contains(602L);
        assertThat(candidateIds).doesNotContain(603L);

        JsonNode offered = candidates.get(candidateIds.indexOf(602L));
        assertThat(offered.path("name").asText()).isEqualTo("Ada Lovelace");
        assertThat(offered.path("weeklyContractHours").asDouble()).isEqualTo(20.0);
        assertThat(offered.path("hoursThisWeek").asDouble()).isEqualTo(hoursOf(602));
    }

    @Test
    void aShiftThatLosesNobodyPublishesNothing() {
        Instant start = Instant.now().plusSeconds(6 * 24 * 3600);
        Long shiftId = createShift(start, 1);
        addEmployee(611, "ACTIVE", List.of(7L), 37.5);
        addEmployee(612, "ACTIVE", List.of(7L), 37.5);
        assign(shiftId, 611);
        Long second = assign(shiftId, 612);

        assertThat(deleteRaw("/v1/shiftassignments/" + second).getStatusCode().value()).isEqualTo(202);

        assertThat(receiveNoneOfType(UNDERSTAFFED, Duration.ofSeconds(3))).isTrue();
    }

    @Test
    void aCancelledLeavePutsTheEmployeeBackInThePool() {
        Instant start = Instant.now().plusSeconds(7 * 24 * 3600);
        Long shiftId = createShift(start, 1);
        addEmployee(621, "ACTIVE", List.of(7L), 37.5);
        addEmployee(622, "ACTIVE", List.of(7L), 37.5);
        Long assignmentId = assign(shiftId, 621);

        LocalDate date = LocalDate.ofInstant(start, ZoneOffset.UTC);
        publish(leaveEvent(LEAVE_APPROVED, 701, 622, date, 1, "APPROVED"));
        awaitCount("SELECT count(*) FROM leave_window WHERE leave_request_id = ?", 1, 701L);

        // The employee withdrew the leave, so the window closes and they are available again.
        publish(leaveEvent(LEAVE_CANCELLED, 701, 622, date, 2, "CANCELLED"));
        awaitCount("SELECT count(*) FROM leave_window WHERE leave_request_id = ? AND status = 'CANCELLED'",
                1, 701L);

        put("/v1/shiftassignments/" + assignmentId,
                new UpdateShiftAssignmentRequest(shiftId, 621, "CANCELLED", null, null), String.class);

        JsonNode data = receiveEvent(UNDERSTAFFED, shiftId).path("data");
        List<Long> candidateIds = new java.util.ArrayList<>();
        data.path("candidates").forEach(candidate -> candidateIds.add(candidate.path("employeeId").asLong()));
        assertThat(candidateIds).contains(622L);
    }

    private Long createShift(Instant start, int required) {
        return post("/v1/shifts", new CreateShiftRequest(2, 3, "Shift " + start, start,
                start.plusSeconds(8 * 3600), "PLANNED", List.of(new RequiredJobRoleRequest(7, required))),
                ShiftResponse.class).getBody().shiftId();
    }

    private Long assign(Long shiftId, long employeeId) {
        return post("/v1/shiftassignments", new CreateShiftAssignmentRequest(shiftId, employeeId, "ASSIGNED"),
                ShiftAssignmentResponse.class).getBody().shiftAssignmentId();
    }

    private void addEmployee(long employeeId, String status, List<Long> jobRoleIds, double weeklyHours) {
        publish(new EventEnvelope(UUID.randomUUID(), "workforce.employee.created.v1", Instant.now(),
                "workforce-service", employeeId, 1, UUID.randomUUID().toString(), null, Map.of(
                        "employeeId", employeeId,
                        "employeeNumber", "EMP-" + employeeId,
                        "userRole", "Employee",
                        "firstName", "Ada",
                        "lastName", "Lovelace",
                        "email", "ada" + employeeId + "@shift.dk",
                        "employmentStatus", status,
                        "jobRoleIds", jobRoleIds,
                        "weeklyContractHours", weeklyHours)));
        awaitCount("SELECT count(*) FROM employee_replica WHERE employee_id = ?", 1, employeeId);
    }

    private EventEnvelope leaveEvent(String eventType, long leaveRequestId, long employeeId, LocalDate date,
                                     long version, String status) {
        return new EventEnvelope(UUID.randomUUID(), eventType, Instant.now(), "leave-service",
                leaveRequestId, version, UUID.randomUUID().toString(), null, Map.of(
                        "leaveRequestId", leaveRequestId,
                        "employeeId", employeeId,
                        "leaveTypeId", 1,
                        "leaveTypeName", "Vacation",
                        "startDate", date.toString(),
                        "endDate", date.toString(),
                        "requestStatus", status,
                        "requestedDatetime", Instant.now().toString(),
                        "decision", Map.of("approverEmployeeId", 1, "decision", "APPROVED",
                                "decisionDatetime", Instant.now().toString())));
    }

    /** True when no event of that type arrives within the window. */
    private boolean receiveNoneOfType(String eventType, Duration window) {
        long deadline = System.currentTimeMillis() + window.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbitTemplate.receive(TEST_QUEUE, 200);
            if (message == null) {
                continue;
            }
            try {
                if (eventType.equals(objectMapper.readTree(message.getBody()).path("eventType").asText())) {
                    return false;
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot parse the event body", e);
            }
        }
        return true;
    }

    /** The hours the employee already works in the ISO week of the shift, as the event reports them. */
    private double hoursOf(long employeeId) {
        return jdbc.queryForObject("""
                select coalesce(sum(extract(epoch from (s.end_datetime - s.start_datetime)) / 3600), 0)
                  from shift_assignment a join shift s on s.shift_id = a.shift_id
                 where a.employee_id = ? and a.deleted = false
                   and a.assignment_status in ('ASSIGNED', 'APPROVED', 'SWAPPED')
                """, Double.class, employeeId);
    }
}
