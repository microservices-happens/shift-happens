package dk.ek.shift_happens.scheduling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.domain.EmployeeReplica;
import dk.ek.shift_happens.scheduling.domain.EmployeeReplicaRepository;
import dk.ek.shift_happens.scheduling.domain.ProcessedEventLog;
import dk.ek.shift_happens.scheduling.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P3.3: the employee replica is a commutative message handler. The same workforce.employee.* events
 * are delivered in every order (exhaustively per employee, and in seeded shuffles across employees
 * with duplicates) through the real {@link EmployeeReplicaService}; the stored replica rows must be
 * the same every time. No Spring and no database: the ordering rule is plain Java.
 *
 * <p>Histories are what Workforce can publish: versions count up from 1 and a tombstone, if there is
 * one, is the last event of its employee.
 */
class EmployeeReplicaShuffledOrderTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-01T08:00:00Z");
    private static final String CREATED = "workforce.employee.created.v1";
    private static final String UPDATED = "workforce.employee.updated.v1";
    private static final String DELETED = "workforce.employee.deleted.v1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // ---- one employee, every possible order ----

    @Test
    void snapshotsOnlyEndWithTheHighestVersionInEveryOrder() {
        List<EventEnvelope> history = List.of(
                snapshot(CREATED, 1, 1, "ACTIVE", List.of(7L), 37.0),
                snapshot(UPDATED, 1, 2, "ACTIVE", List.of(7L, 9L), 30.0),
                snapshot(UPDATED, 1, 3, "INACTIVE", List.of(9L), 20.0));

        Map<Long, EmployeeReplica> expected = assertSameStateInEveryOrder(history);

        EmployeeReplica employee = expected.get(1L);
        assertThat(employee.aggregateVersion()).isEqualTo(3);
        assertThat(employee.employmentStatus()).isEqualTo("INACTIVE");
        assertThat(employee.jobRoleIds()).containsExactly(9L);
        assertThat(employee.weeklyContractHours()).isEqualTo(20.0);
        assertThat(employee.deletedAt()).isNull();
    }

    @Test
    void aTombstoneAfterOneSnapshotIsRetainedInEveryOrder() {
        List<EventEnvelope> history = List.of(
                snapshot(CREATED, 2, 1, "ACTIVE", List.of(7L), 37.0),
                tombstone(2, 2));

        Map<Long, EmployeeReplica> expected = assertSameStateInEveryOrder(history);

        assertThat(expected.get(2L).isActive()).isFalse();
        assertThat(expected.get(2L).deletedAt()).isEqualTo(OCCURRED_AT.plusSeconds(2));
        assertThat(expected.get(2L).aggregateVersion()).isEqualTo(2);
    }

    @Test
    void aTombstoneAfterSeveralSnapshotsKeepsTheLastSnapshotAndStaysDeletedInEveryOrder() {
        List<EventEnvelope> history = List.of(
                snapshot(CREATED, 3, 1, "ACTIVE", List.of(7L), 37.0),
                snapshot(UPDATED, 3, 2, "ACTIVE", List.of(7L, 9L), 30.0),
                snapshot(UPDATED, 3, 3, "ACTIVE", List.of(9L), 20.0),
                tombstone(3, 4));

        Map<Long, EmployeeReplica> expected = assertSameStateInEveryOrder(history);

        EmployeeReplica employee = expected.get(3L);
        assertThat(employee.isActive()).isFalse();
        assertThat(employee.deletedAt()).isNotNull();
        assertThat(employee.aggregateVersion()).isEqualTo(4);
        assertThat(employee.firstName()).as("the last snapshot before the delete").isEqualTo("First3");
        assertThat(employee.jobRoleIds()).containsExactly(9L);
    }

    @Test
    void aTombstoneThatArrivesBeforeTheCreateStillWins() {
        // The smallest out-of-order case, spelled out: delete (v2) overtakes create (v1).
        Map<Long, EmployeeReplica> state = deliver(List.of(
                tombstone(4, 2),
                snapshot(CREATED, 4, 1, "ACTIVE", List.of(7L), 37.0)), new RememberingLog());

        assertThat(state.get(4L).isActive()).as("a late create must not make a deleted employee assignable")
                .isFalse();
        assertThat(state.get(4L).deletedAt()).isNotNull();
        assertThat(state.get(4L).aggregateVersion()).isEqualTo(2);
    }

    // ---- several employees interleaved, with duplicates ----

    @Test
    void interleavedEmployeesWithRedeliveriesEndInTheSameStateForEverySeed() {
        List<EventEnvelope> events = severalEmployees();
        Map<Long, EmployeeReplica> expected = deliver(events, new RememberingLog());

        for (long seed = 1; seed <= 200; seed++) {
            Random random = new Random(seed);
            List<EventEnvelope> noisy = new ArrayList<>(events);
            // Redelivery: the same envelope (same eventId) arrives a second or third time.
            for (EventEnvelope event : events) {
                for (int extra = random.nextInt(3); extra > 0; extra--) {
                    noisy.add(event);
                }
            }
            Collections.shuffle(noisy, random);

            assertThat(deliver(noisy, new RememberingLog())).as("seed %d: %s", seed, describe(noisy))
                    .isEqualTo(expected);
        }
    }

    @Test
    void theVersionRuleAloneMakesRedeliveryHarmlessEvenWithoutTheProcessedEventLog() {
        // processed_events is the first line of defence; this proves the handler does not depend on it.
        List<EventEnvelope> events = severalEmployees();
        Map<Long, EmployeeReplica> expected = deliver(events, new RememberingLog());

        for (long seed = 1; seed <= 100; seed++) {
            List<EventEnvelope> twice = new ArrayList<>(events);
            twice.addAll(events);
            Collections.shuffle(twice, new Random(seed));

            assertThat(deliver(twice, new ForgetfulLog())).as("seed %d: %s", seed, describe(twice))
                    .isEqualTo(expected);
        }
    }

    // ---- harness ----

    private List<EventEnvelope> severalEmployees() {
        return List.of(
                snapshot(CREATED, 10, 1, "ACTIVE", List.of(7L), 37.0),
                snapshot(UPDATED, 10, 2, "ACTIVE", List.of(7L, 9L), 37.0),
                snapshot(UPDATED, 10, 3, "ACTIVE", List.of(9L), 30.0),
                snapshot(CREATED, 11, 1, "ACTIVE", List.of(7L), 37.0),
                snapshot(UPDATED, 11, 2, "ACTIVE", List.of(8L), 32.0),
                tombstone(11, 3),
                snapshot(CREATED, 12, 1, "ACTIVE", List.of(), 15.0),
                tombstone(12, 2),
                snapshot(CREATED, 13, 1, "ACTIVE", List.of(7L), 37.0),
                snapshot(UPDATED, 13, 2, "INACTIVE", List.of(7L), 37.0),
                snapshot(UPDATED, 13, 3, "ACTIVE", List.of(7L), 37.0),
                snapshot(UPDATED, 13, 4, "ACTIVE", List.of(7L, 8L), 25.0),
                tombstone(13, 5));
    }

    /** Every permutation of the history must give the state of the in-order delivery, which is returned. */
    private Map<Long, EmployeeReplica> assertSameStateInEveryOrder(List<EventEnvelope> history) {
        Map<Long, EmployeeReplica> expected = deliver(history, new RememberingLog());
        for (List<EventEnvelope> order : permutations(history)) {
            assertThat(deliver(order, new RememberingLog())).as("order %s", describe(order)).isEqualTo(expected);
        }
        return expected;
    }

    /** A fresh replica per delivery order; the result is every stored row by employeeId. */
    private Map<Long, EmployeeReplica> deliver(List<EventEnvelope> order, ProcessedEventLog log) {
        InMemoryReplica replica = new InMemoryReplica();
        EmployeeReplicaService service = new EmployeeReplicaService(replica, log, mapper);
        order.forEach(service::apply);
        return new TreeMap<>(replica.byEmployeeId);
    }

    private static EventEnvelope snapshot(String eventType, long employeeId, long version, String status,
                                          List<Long> jobRoleIds, double weeklyContractHours) {
        Map<String, Object> data = new HashMap<>();
        data.put("employeeId", employeeId);
        data.put("employeeNumber", "EMP-" + employeeId);
        data.put("userRole", "Employee");
        data.put("firstName", "First" + version);
        data.put("lastName", "Last" + version);
        data.put("email", "emp" + employeeId + "@shift.dk");
        data.put("employmentStatus", status);
        data.put("primaryWorkLocationId", version);
        data.put("jobRoleIds", jobRoleIds);
        data.put("weeklyContractHours", weeklyContractHours);
        return new EventEnvelope(UUID.randomUUID(), eventType, OCCURRED_AT.plusSeconds(version),
                "workforce-service", employeeId, version, "corr-" + employeeId, null, data);
    }

    private static EventEnvelope tombstone(long employeeId, long version) {
        Instant deletedAt = OCCURRED_AT.plusSeconds(version);
        return new EventEnvelope(UUID.randomUUID(), DELETED, deletedAt, "workforce-service", employeeId, version,
                "corr-" + employeeId, null,
                Map.of("entityType", "employee", "entityId", employeeId, "deletedAt", deletedAt.toString()));
    }

    private static String describe(List<EventEnvelope> order) {
        StringBuilder text = new StringBuilder();
        for (EventEnvelope event : order) {
            text.append(event.aggregateId()).append(event.eventType().endsWith(".deleted.v1") ? ":T" : ":S")
                    .append(event.aggregateVersion()).append(' ');
        }
        return text.toString().trim();
    }

    private static <T> List<List<T>> permutations(List<T> items) {
        if (items.isEmpty()) {
            return List.of(List.of());
        }
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            List<T> rest = new ArrayList<>(items);
            T head = rest.remove(i);
            for (List<T> tail : permutations(rest)) {
                List<T> order = new ArrayList<>();
                order.add(head);
                order.addAll(tail);
                result.add(order);
            }
        }
        return result;
    }

    /** processed_events as it behaves in production: a recorded eventId is recognised. */
    private static final class RememberingLog implements ProcessedEventLog {
        private final Set<UUID> seen = new HashSet<>();

        @Override
        public boolean contains(UUID eventId) {
            return seen.contains(eventId);
        }

        @Override
        public void record(UUID eventId) {
            seen.add(eventId);
        }
    }

    /** Never recognises anything, so every redelivery reaches the version rule. */
    private static final class ForgetfulLog implements ProcessedEventLog {
        @Override
        public boolean contains(UUID eventId) {
            return false;
        }

        @Override
        public void record(UUID eventId) {
            // forgets on purpose
        }
    }

    /** Behaves like the JPA adapter: one row per employeeId, save replaces it. */
    private static final class InMemoryReplica implements EmployeeReplicaRepository {
        private final Map<Long, EmployeeReplica> byEmployeeId = new HashMap<>();

        @Override
        public Optional<EmployeeReplica> findByEmployeeId(long employeeId) {
            return Optional.ofNullable(byEmployeeId.get(employeeId));
        }

        @Override
        public List<EmployeeReplica> findAll() {
            return List.copyOf(byEmployeeId.values());
        }

        @Override
        public EmployeeReplica save(EmployeeReplica employee) {
            byEmployeeId.put(employee.employeeId(), employee);
            return employee;
        }
    }
}
