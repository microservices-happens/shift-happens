package dk.ek.shift_happens.scheduling;

import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command role consumes workforce.employee.* into its replica (P2.8). Workforce only publishes
 * created.v1 today, so updated/deleted are published here exactly as the contracts describe them.
 */
class EmployeeReplicaIT extends AbstractCommandIT {

    private static final String EMPLOYEE_CREATED = "workforce.employee.created.v1";
    private static final String EMPLOYEE_UPDATED = "workforce.employee.updated.v1";
    private static final String EMPLOYEE_DELETED = "workforce.employee.deleted.v1";

    @Test
    void aCreatedAndThenUpdatedEmployeeEndsInTheReplica() {
        employeeEvent(EMPLOYEE_CREATED, 201, 1, "ACTIVE", List.of(7L));
        awaitCount("SELECT count(*) FROM employee_replica WHERE employee_id = ?", 1, 201L);

        employeeEvent(EMPLOYEE_UPDATED, 201, 2, "INACTIVE", List.of(7L, 9L));
        awaitCount("SELECT aggregate_version FROM employee_replica WHERE employee_id = ?", 2, 201L);

        assertThat(jdbc.queryForObject(
                "SELECT employment_status FROM employee_replica WHERE employee_id = ?", String.class, 201L))
                .isEqualTo("INACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT job_role_ids FROM employee_replica WHERE employee_id = ?", String.class, 201L))
                .isEqualTo("7,9");
    }

    @Test
    void anOlderSnapshotIsIgnored() {
        employeeEvent(EMPLOYEE_CREATED, 202, 5, "ACTIVE", List.of(7L));
        awaitCount("SELECT aggregate_version FROM employee_replica WHERE employee_id = ?", 5, 202L);

        EventEnvelope older = employeeEvent(EMPLOYEE_UPDATED, 202, 2, "INACTIVE", List.of(9L));
        awaitCount("SELECT count(*) FROM processed_events WHERE event_id = ?", 1, older.eventId());

        assertThat(jdbc.queryForObject(
                "SELECT aggregate_version FROM employee_replica WHERE employee_id = ?", Long.class, 202L))
                .isEqualTo(5L);
        assertThat(jdbc.queryForObject(
                "SELECT employment_status FROM employee_replica WHERE employee_id = ?", String.class, 202L))
                .isEqualTo("ACTIVE");
    }

    @Test
    void aRedeliveredEventIsAppliedOnce() {
        EventEnvelope envelope = envelope(EMPLOYEE_CREATED, 203, 1, employeeData(203, "ACTIVE", List.of(7L)));

        publish(envelope);
        publish(envelope);

        awaitCount("SELECT count(*) FROM employee_replica WHERE employee_id = ?", 1, 203L);
        awaitCount("SELECT count(*) FROM processed_events WHERE event_id = ?", 1, envelope.eventId());
        assertThat(jdbc.queryForObject(
                "SELECT aggregate_version FROM employee_replica WHERE employee_id = ?", Long.class, 203L))
                .isEqualTo(1L);
    }

    @Test
    void aTombstoneIsKeptAndALateUpdateCannotResurrectTheEmployee() {
        employeeEvent(EMPLOYEE_CREATED, 204, 1, "ACTIVE", List.of(7L));
        awaitCount("SELECT count(*) FROM employee_replica WHERE employee_id = ?", 1, 204L);

        publish(envelope(EMPLOYEE_DELETED, 204, 2,
                Map.of("entityType", "employee", "entityId", 204, "deletedAt", Instant.now().toString())));
        awaitCount("SELECT aggregate_version FROM employee_replica WHERE employee_id = ?", 2, 204L);
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM employee_replica WHERE employee_id = ?", Instant.class, 204L)).isNotNull();

        // A late, lower-version update must not bring the employee back.
        employeeEvent(EMPLOYEE_UPDATED, 204, 1, "ACTIVE", List.of(7L));
        assertThat(jdbc.queryForObject(
                "SELECT deleted_at FROM employee_replica WHERE employee_id = ?", Instant.class, 204L)).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT aggregate_version FROM employee_replica WHERE employee_id = ?", Long.class, 204L))
                .isEqualTo(2L);
    }

    private EventEnvelope employeeEvent(String eventType, long employeeId, long version, String status,
                               List<Long> jobRoleIds) {
        EventEnvelope envelope = envelope(eventType, employeeId, version,
                employeeData(employeeId, status, jobRoleIds));
        publish(envelope);
        return envelope;
    }

    private EventEnvelope envelope(String eventType, long employeeId, long version, Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), eventType, Instant.now(), "workforce-service",
                employeeId, version, UUID.randomUUID().toString(), null, data);
    }

    /** The payload of contracts/events/payloads/employee.schema.json, trimmed to what the replica keeps. */
    private static Map<String, Object> employeeData(long employeeId, String status, List<Long> jobRoleIds) {
        return Map.of(
                "employeeId", employeeId,
                "employeeNumber", "EMP-" + employeeId,
                "userRole", "Employee",
                "firstName", "Grace",
                "lastName", "Hopper",
                "email", "grace" + employeeId + "@shift.dk",
                "employmentStatus", status,
                "jobRoleIds", jobRoleIds);
    }
}
