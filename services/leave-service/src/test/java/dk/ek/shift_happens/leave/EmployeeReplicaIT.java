package dk.ek.shift_happens.leave;

import dk.ek.shift_happens.leave.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The command role consumes workforce.employee.* into its replica (#44). Workforce only publishes
 * created.v1 today, so updated/deleted are published here exactly as the contracts describe them.
 */
class EmployeeReplicaIT extends AbstractCommandIT {

    private static final String EMPLOYEE_CREATED = "workforce.employee.created.v1";
    private static final String EMPLOYEE_UPDATED = "workforce.employee.updated.v1";
    private static final String EMPLOYEE_DELETED = "workforce.employee.deleted.v1";

    @Test
    void aCreatedEmployeeEndsInTheReplicaAndAHigherVersionIsApplied() {
        employeeEvent(EMPLOYEE_CREATED, 301, 1, "ACTIVE");
        awaitCount("SELECT count(*) FROM employee_replica WHERE employee_id = ?", 1, 301L);

        employeeEvent(EMPLOYEE_UPDATED, 301, 2, "INACTIVE");
        awaitCount("SELECT aggregate_version FROM employee_replica WHERE employee_id = ?", 2, 301L);

        assertThat(jdbc.queryForObject(
                "SELECT employment_status FROM employee_replica WHERE employee_id = ?", String.class, 301L))
                .isEqualTo("INACTIVE");
    }

    @Test
    void anOutOfOrderOlderUpdateIsIgnored() {
        employeeEvent(EMPLOYEE_CREATED, 302, 5, "ACTIVE");
        awaitCount("SELECT aggregate_version FROM employee_replica WHERE employee_id = ?", 5, 302L);

        EventEnvelope older = employeeEvent(EMPLOYEE_UPDATED, 302, 2, "INACTIVE");
        awaitCount("SELECT count(*) FROM processed_events WHERE event_id = ?", 1, older.eventId());

        assertThat(jdbc.queryForObject(
                "SELECT employment_status FROM employee_replica WHERE employee_id = ?", String.class, 302L))
                .isEqualTo("ACTIVE");
    }

    @Test
    void aRedeliveredEventIsAppliedOnce() {
        EventEnvelope envelope = envelope(EMPLOYEE_CREATED, 303, 1, employeeData(303, "ACTIVE"));

        publish(envelope);
        publish(envelope);

        awaitCount("SELECT count(*) FROM employee_replica WHERE employee_id = ?", 1, 303L);
        awaitCount("SELECT count(*) FROM processed_events WHERE event_id = ?", 1, envelope.eventId());
    }

    @Test
    void aDeleteBeforeTheCreateStaysDeleted() {
        publish(envelope(EMPLOYEE_DELETED, 304, 2, tombstone(304)));
        awaitCount("SELECT count(*) FROM employee_replica WHERE employee_id = ? AND deleted_at IS NOT NULL", 1, 304L);

        EventEnvelope lateCreate = employeeEvent(EMPLOYEE_CREATED, 304, 1, "ACTIVE");
        awaitCount("SELECT count(*) FROM processed_events WHERE event_id = ?", 1, lateCreate.eventId());

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM employee_replica WHERE employee_id = ? AND deleted_at IS NOT NULL",
                Integer.class, 304L)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT aggregate_version FROM employee_replica WHERE employee_id = ?", Long.class, 304L))
                .isEqualTo(2L);
    }

    @Test
    void aPoisonMessageEndsInTheDeadLetterQueue() {
        UUID poisonId = UUID.randomUUID();
        // No employeeId: can never be applied, so after three attempts it is dead-lettered.
        publish(new EventEnvelope(poisonId, EMPLOYEE_CREATED, Instant.now(), "workforce-service", 305, 1,
                UUID.randomUUID().toString(), null, Map.of("firstName", "Nobody")));

        AtomicReference<Message> dead = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Message message = rabbitTemplate.receive("leave.command.dlq", 300);
            while (message != null) {
                if (new String(message.getBody()).contains(poisonId.toString())) {
                    dead.set(message);
                    break;
                }
                message = rabbitTemplate.receive("leave.command.dlq", 300);
            }
            assertThat(dead.get()).isNotNull();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, poisonId)).isZero();
    }

    private EventEnvelope employeeEvent(String eventType, long employeeId, long version, String status) {
        EventEnvelope envelope = envelope(eventType, employeeId, version, employeeData(employeeId, status));
        publish(envelope);
        return envelope;
    }

    private void publish(EventEnvelope envelope) {
        rabbitTemplate.convertAndSend(eventsExchange.getName(), envelope.eventType(), envelope);
    }

    private EventEnvelope envelope(String eventType, long employeeId, long version, Map<String, Object> data) {
        return new EventEnvelope(UUID.randomUUID(), eventType, Instant.now(), "workforce-service",
                employeeId, version, UUID.randomUUID().toString(), null, data);
    }

    private void awaitCount(String sql, int expected, Object... args) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(sql, Integer.class, args)).isEqualTo(expected));
    }

    private void awaitCount(String sql, long expected, Object... args) {
        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(jdbc.queryForObject(sql, Long.class, args)).isEqualTo(expected));
    }

    /** The payload of contracts/events/payloads/employee.schema.json, trimmed to what the replica keeps. */
    private static Map<String, Object> employeeData(long employeeId, String status) {
        return Map.of(
                "employeeId", employeeId,
                "employeeNumber", "EMP-" + employeeId,
                "userRole", "Employee",
                "firstName", "Grace",
                "lastName", "Hopper",
                "email", "grace" + employeeId + "@shift.dk",
                "employmentStatus", status,
                "jobRoleIds", java.util.List.of(7L),
                "weeklyContractHours", 37.5);
    }

    /** The payload of contracts/events/payloads/tombstone.schema.json. */
    private static Map<String, Object> tombstone(long employeeId) {
        return Map.of("entityType", "employee", "entityId", employeeId, "deletedAt", Instant.now().toString());
    }
}
