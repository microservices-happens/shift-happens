package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.scheduling.api.dto.CreateRequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.api.dto.CreateShiftRequest;
import dk.ek.shift_happens.scheduling.api.dto.RequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.api.dto.ShiftRequiredJobRoleResponse;
import dk.ek.shift_happens.scheduling.api.dto.ShiftResponse;
import dk.ek.shift_happens.scheduling.api.dto.UpdateRequiredJobRoleRequest;
import dk.ek.shift_happens.scheduling.api.dto.UpdateShiftRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /v1/shiftrequiredjobroles: the shift aggregate owns its staffing, so every change republishes the
 * whole shift as scheduling.shift.updated.v1 and bumps its version.
 */
class RequiredJobRoleCommandIT extends AbstractCommandIT {

    private static final String SHIFT_UPDATED = "scheduling.shift.updated.v1";
    private static final Instant START = Instant.parse("2026-12-01T06:00:00Z");
    private static final Instant END = Instant.parse("2026-12-01T14:00:00Z");

    @Test
    void addingARequiredJobRoleRepublishesTheShift() {
        Long shiftId = createShift();

        ResponseEntity<ShiftRequiredJobRoleResponse> response = post("/v1/shiftrequiredjobroles",
                new CreateRequiredJobRoleRequest(shiftId, 11, 3), ShiftRequiredJobRoleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().shiftRequiredJobRoleId()).isNotNull();
        assertThat(response.getBody().shiftId()).isEqualTo(shiftId);
        assertThat(response.getBody().requiredEmployeeCount()).isEqualTo(3);

        JsonNode event = receiveEvent(SHIFT_UPDATED, shiftId);
        assertThat(event.path("aggregateVersion").asLong()).isEqualTo(2);
        assertThat(event.path("data").path("requiredJobRoles").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT aggregate_version FROM shift WHERE shift_id = ?", Long.class, shiftId))
                .isEqualTo(2L);
    }

    @Test
    void replacingARowChangesItsCount() {
        Long shiftId = createShift();
        Long rowId = post("/v1/shiftrequiredjobroles", new CreateRequiredJobRoleRequest(shiftId, 11, 1),
                ShiftRequiredJobRoleResponse.class).getBody().shiftRequiredJobRoleId();

        // The POST published v2; consume it so the assertion below reads the update's event.
        assertThat(receiveEvent(SHIFT_UPDATED, shiftId).path("aggregateVersion").asLong()).isEqualTo(2);

        ResponseEntity<ShiftRequiredJobRoleResponse> response = put("/v1/shiftrequiredjobroles/" + rowId,
                new UpdateRequiredJobRoleRequest(shiftId, 11, 5), ShiftRequiredJobRoleResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().shiftRequiredJobRoleId()).isEqualTo(rowId);
        assertThat(response.getBody().requiredEmployeeCount()).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "SELECT required_employee_count FROM shift_required_job_role WHERE shift_required_job_role_id = ?",
                Integer.class, rowId)).isEqualTo(5);
        assertThat(receiveEvent(SHIFT_UPDATED, shiftId).path("aggregateVersion").asLong()).isEqualTo(3);
    }

    @Test
    void removingARowDropsIt() {
        Long shiftId = createShift();
        Long rowId = post("/v1/shiftrequiredjobroles", new CreateRequiredJobRoleRequest(shiftId, 11, 2),
                ShiftRequiredJobRoleResponse.class).getBody().shiftRequiredJobRoleId();

        // The POST published v2 with two roles; consume it so the delete's event is the one asserted.
        assertThat(receiveEvent(SHIFT_UPDATED, shiftId).path("data").path("requiredJobRoles").size())
                .isEqualTo(2);

        assertThat(deleteRaw("/v1/shiftrequiredjobroles/" + rowId).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM shift_required_job_role WHERE shift_required_job_role_id = ?",
                Integer.class, rowId)).isZero();
        assertThat(receiveEvent(SHIFT_UPDATED, shiftId).path("data").path("requiredJobRoles").size()).isEqualTo(1);
    }

    @Test
    void aDuplicateJobRoleIsAConflict() {
        Long shiftId = createShift();

        ResponseEntity<String> response = post("/v1/shiftrequiredjobroles",
                new CreateRequiredJobRoleRequest(shiftId, 7, 1), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("already requires job role 7");
    }

    @Test
    void anUnknownRowIsNotFound() {
        assertThat(deleteRaw("/v1/shiftrequiredjobroles/9999999").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(put("/v1/shiftrequiredjobroles/9999999",
                new UpdateRequiredJobRoleRequest(1, 11, 1), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aCancelledShiftTakesNoNewStaff() {
        Long shiftId = createShift();
        put("/v1/shifts/" + shiftId, new UpdateShiftRequest(2, 3, "Morning", START, END, "CANCELLED", null),
                ShiftResponse.class);

        ResponseEntity<String> response = post("/v1/shiftrequiredjobroles",
                new CreateRequiredJobRoleRequest(shiftId, 11, 1), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("the shift is cancelled");
    }

    private Long createShift() {
        return post("/v1/shifts", new CreateShiftRequest(2, 3, "Morning", START, END, "PLANNED",
                List.of(new RequiredJobRoleRequest(7, 2))), ShiftResponse.class).getBody().shiftId();
    }
}
