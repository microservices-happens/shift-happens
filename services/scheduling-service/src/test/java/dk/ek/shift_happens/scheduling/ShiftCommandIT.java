package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftRequest;
import dk.ek.shift_happens.scheduling.api.dto.RequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * REST -> PostgreSQL -> outbox -> RabbitMQ for shifts, against the real engines.
 * Assertions are scoped to the shift this test created, because the tests share one database.
 */
class ShiftCommandIT extends AbstractCommandIT {

    private static final String SHIFT_CREATED = "scheduling.shift.created.v1";
    private static final String SHIFT_UPDATED = "scheduling.shift.updated.v1";
    private static final String SHIFT_DELETED = "scheduling.shift.deleted.v1";
    private static final Instant START = Instant.parse("2026-11-02T06:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T14:00:00Z");

    @Test
    void healthEndpointIsPublicAndUp() {
        assertThat(http.getForEntity("/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void creatingAShiftStoresItsJobRolesAndPublishesTheEvent() {
        ResponseEntity<ShiftResponse> response = createMorningShift();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long shiftId = Objects.requireNonNull(response.getBody()).shiftId();
        assertThat(response.getBody().shiftStatus()).isEqualTo("PLANNED");
        assertThat(response.getBody().requiredJobRoles()).hasSize(2);
        assertThat(response.getBody().requiredJobRoles().get(0).shiftRequiredJobRoleId()).isNotNull();

        assertThat(jdbc.queryForObject("SELECT shift_name FROM shift WHERE shift_id = ?", String.class, shiftId))
                .isEqualTo("Morning");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shift_required_job_role WHERE shift_id = ?", Integer.class, shiftId))
                .isEqualTo(2);

        JsonNode event = receiveEvent(SHIFT_CREATED, shiftId);
        assertThat(event.path("producer").asText()).isEqualTo("scheduling-service");
        assertThat(event.path("aggregateVersion").asLong()).isEqualTo(1);
        assertThat(event.path("correlationId").asText()).isNotBlank();

        JsonNode data = event.path("data");
        assertThat(data.path("shiftId").asLong()).isEqualTo(shiftId);
        assertThat(data.path("shiftStatus").asText()).isEqualTo("PLANNED");
        assertThat(data.path("startDatetime").asText()).isEqualTo("2026-11-02T06:00:00Z");
        assertThat(data.path("endDatetime").asText()).isEqualTo("2026-11-02T14:00:00Z");
        assertThat(data.path("requiredJobRoles").size()).isEqualTo(2);
        assertThat(data.path("requiredJobRoles").get(0).path("jobRoleId").asLong()).isEqualTo(7);
        assertThat(data.path("requiredJobRoles").get(0).path("requiredEmployeeCount").asInt()).isEqualTo(2);

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE published_at IS NOT NULL AND aggregate_id = ?",
                Integer.class, shiftId)).isEqualTo(1));
    }

    @Test
    void theCorrelationIdHeaderIsCopiedIntoTheEvent() {
        String correlationId = UUID.randomUUID().toString();

        ResponseEntity<ShiftResponse> response = http.exchange("/v1/shifts", HttpMethod.POST,
                new HttpEntity<>(morningShift(), headersFor(UUID.randomUUID().toString(), correlationId)),
                ShiftResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        Long shiftId = Objects.requireNonNull(response.getBody()).shiftId();
        assertThat(receiveEvent(SHIFT_CREATED, shiftId).path("correlationId").asText()).isEqualTo(correlationId);
    }

    @Test
    void aShiftMustNotEndBeforeItStarts() {
        ResponseEntity<String> response = http.postForEntity("/v1/shifts", new HttpEntity<>(
                new CreateShiftRequest(2, 3, "Broken", END, START, "PLANNED",
                        List.of(new RequiredJobRoleRequest(7, 1))),
                headersFor(UUID.randomUUID().toString(), null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
        assertThat(response.getBody()).contains("endDatetime must be after startDatetime");
    }

    @Test
    void anIdempotencyKeyIsRequired() {
        ResponseEntity<String> response = http.postForEntity("/v1/shifts",
                new HttpEntity<>(morningShift(), headersFor(null, null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Idempotency-Key");
    }

    @Test
    void anIdempotencyKeyMustBeAUuid() {
        ResponseEntity<String> response = http.postForEntity("/v1/shifts",
                new HttpEntity<>(morningShift(), headersFor("not-a-uuid", null)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("must be a UUID");
    }

    @Test
    void updatingAShiftPublishesTheNewSnapshot() {
        Long shiftId = createMorningShift().getBody().shiftId();

        ResponseEntity<ShiftResponse> response = put("/v1/shifts/" + shiftId,
                new UpdateShiftRequest(2, 3, "Evening", START, END, "PUBLISHED", null), ShiftResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(Objects.requireNonNull(response.getBody()).shiftName()).isEqualTo("Evening");
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM shift WHERE shift_id = ?", Long.class, shiftId))
                .isEqualTo(2L);

        JsonNode event = receiveEvent(SHIFT_UPDATED, shiftId);
        assertThat(event.path("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(event.path("data").path("shiftName").asText()).isEqualTo("Evening");
        assertThat(event.path("data").path("shiftStatus").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    void anImpossibleStatusChangeIsAConflict() {
        Long shiftId = createMorningShift().getBody().shiftId();
        assertThat(put("/v1/shifts/" + shiftId,
                new UpdateShiftRequest(2, 3, "Morning", START, END, "CANCELLED", null), ShiftResponse.class)
                .getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        ResponseEntity<String> response = put("/v1/shifts/" + shiftId,
                new UpdateShiftRequest(2, 3, "Morning", START, END, "PUBLISHED", null), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("from CANCELLED to PUBLISHED");
    }

    @Test
    void deletingAShiftPublishesATombstoneAndKeepsTheRow() {
        Long shiftId = createMorningShift().getBody().shiftId();

        assertThat(deleteRaw("/v1/shifts/" + shiftId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT deleted FROM shift WHERE shift_id = ?", Boolean.class, shiftId))
                .isTrue();

        JsonNode event = receiveEvent(SHIFT_DELETED, shiftId);
        assertThat(event.path("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(event.path("data").path("entityType").asText()).isEqualTo("shift");
        assertThat(event.path("data").path("entityId").asLong()).isEqualTo(shiftId);
    }

    @Test
    void aDeletedShiftIsGoneForUpdatesAndDeletes() {
        Long shiftId = createMorningShift().getBody().shiftId();
        deleteRaw("/v1/shifts/" + shiftId);

        assertThat(put("/v1/shifts/" + shiftId,
                new UpdateShiftRequest(2, 3, "Morning", START, END, "PUBLISHED", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(deleteRaw("/v1/shifts/" + shiftId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void anUnknownShiftIsNotFound() {
        assertThat(put("/v1/shifts/9999999",
                new UpdateShiftRequest(2, 3, "Ghost", START, END, "PLANNED", null), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(deleteRaw("/v1/shifts/9999999").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private ResponseEntity<ShiftResponse> createMorningShift() {
        return post("/v1/shifts", morningShift(), ShiftResponse.class);
    }

    private static CreateShiftRequest morningShift() {
        return new CreateShiftRequest(2, 3, "Morning", START, END, "PLANNED",
                List.of(new RequiredJobRoleRequest(7, 2), new RequiredJobRoleRequest(9, 1)));
    }
}
