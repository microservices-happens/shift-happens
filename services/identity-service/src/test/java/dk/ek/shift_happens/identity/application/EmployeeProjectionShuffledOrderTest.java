package dk.ek.shift_happens.identity.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import dk.ek.shift_happens.identity.domain.ProcessedEventLog;
import dk.ek.shift_happens.identity.infrastructure.messaging.EventEnvelope;
import dk.ek.shift_happens.identity.infrastructure.security.PepperedPasswordEncoder;
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
 * P3.3: the account projection is a commutative message handler. The same workforce.employee.* events
 * are delivered in every order (exhaustively per employee, and in seeded shuffles across employees
 * with duplicates) through the real {@link EmployeeProjectionService}; the stored accounts must be the
 * same every time. No Spring and no database: the ordering rule is plain Java.
 *
 * <p>Histories are what Workforce can publish: versions count up from 1 and a tombstone, if there is
 * one, is the last event of its employee. The fake repository hands out no {@code accountId}: that is
 * a surrogate key assigned in arrival order by the database, not projected state.
 */
class EmployeeProjectionShuffledOrderTest {

    private static final Instant OCCURRED_AT = Instant.parse("2026-11-01T08:00:00Z");
    private static final String CREATED = "workforce.employee.created.v1";
    private static final String UPDATED = "workforce.employee.updated.v1";
    private static final String DELETED = "workforce.employee.deleted.v1";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // ---- one employee, every possible order ----

    @Test
    void snapshotsOnlyEndWithTheHighestVersionInEveryOrder() {
        List<EventEnvelope> history = List.of(
                snapshot(CREATED, 1, 1, "ada@shift.dk", "Employee", "ACTIVE"),
                snapshot(UPDATED, 1, 2, "ada.l@shift.dk", "Manager", "ACTIVE"),
                snapshot(UPDATED, 1, 3, "ada.lovelace@shift.dk", "Manager", "INACTIVE"));

        Map<Long, Account> expected = assertSameStateInEveryOrder(history);

        Account account = expected.get(1L);
        assertThat(account.email()).isEqualTo("ada.lovelace@shift.dk");
        assertThat(account.role()).isEqualTo("Manager");
        assertThat(account.active()).isFalse();
        assertThat(account.aggregateVersion()).isEqualTo(3);
    }

    @Test
    void aTombstoneAfterOneSnapshotDisablesTheAccountInEveryOrder() {
        List<EventEnvelope> history = List.of(
                snapshot(CREATED, 2, 1, "bo@shift.dk", "Employee", "ACTIVE"),
                tombstone(2, 2));

        Map<Long, Account> expected = assertSameStateInEveryOrder(history);

        assertThat(expected.get(2L).active()).isFalse();
        assertThat(expected.get(2L).aggregateVersion()).isEqualTo(2);
        assertThat(expected.get(2L).email()).isEqualTo("bo@shift.dk");
    }

    @Test
    void aTombstoneAfterSeveralSnapshotsKeepsTheLastSnapshotAndStaysDisabledInEveryOrder() {
        List<EventEnvelope> history = List.of(
                snapshot(CREATED, 3, 1, "cy@shift.dk", "Employee", "ACTIVE"),
                snapshot(UPDATED, 3, 2, "cy.2@shift.dk", "Employee", "ACTIVE"),
                snapshot(UPDATED, 3, 3, "cy.3@shift.dk", "Manager", "ACTIVE"),
                tombstone(3, 4));

        Map<Long, Account> expected = assertSameStateInEveryOrder(history);

        Account account = expected.get(3L);
        assertThat(account.active()).isFalse();
        assertThat(account.aggregateVersion()).isEqualTo(4);
        assertThat(account.email()).as("the last snapshot before the delete").isEqualTo("cy.3@shift.dk");
        assertThat(account.role()).isEqualTo("Manager");
    }

    @Test
    void aTombstoneThatArrivesBeforeTheCreateStillWins() {
        // The smallest out-of-order case, spelled out: delete (v2) overtakes create (v1).
        Map<Long, Account> state = deliver(List.of(
                tombstone(4, 2),
                snapshot(CREATED, 4, 1, "di@shift.dk", "Employee", "ACTIVE")), new RememberingLog());

        assertThat(state.get(4L).active()).as("a late create must not resurrect a deleted employee").isFalse();
        assertThat(state.get(4L).canLogInWithProvider()).isFalse();
        assertThat(state.get(4L).aggregateVersion()).isEqualTo(2);
    }

    // ---- several employees interleaved, with duplicates ----

    @Test
    void interleavedEmployeesWithRedeliveriesEndInTheSameStateForEverySeed() {
        List<EventEnvelope> events = severalEmployees();
        Map<Long, Account> expected = deliver(events, new RememberingLog());

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
        Map<Long, Account> expected = deliver(events, new RememberingLog());

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
                snapshot(CREATED, 10, 1, "ada@shift.dk", "Employee", "ACTIVE"),
                snapshot(UPDATED, 10, 2, "ada.l@shift.dk", "Manager", "ACTIVE"),
                snapshot(UPDATED, 10, 3, "ada.lovelace@shift.dk", "Manager", "ACTIVE"),
                snapshot(CREATED, 11, 1, "bo@shift.dk", "Employee", "ACTIVE"),
                snapshot(UPDATED, 11, 2, "bo.2@shift.dk", "Employee", "ACTIVE"),
                tombstone(11, 3),
                snapshot(CREATED, 12, 1, "cy@shift.dk", "Administrator", "ACTIVE"),
                tombstone(12, 2),
                snapshot(CREATED, 13, 1, "di@shift.dk", "Employee", "ACTIVE"),
                snapshot(UPDATED, 13, 2, "di@shift.dk", "Employee", "INACTIVE"),
                snapshot(UPDATED, 13, 3, "di@shift.dk", "Employee", "ACTIVE"),
                snapshot(UPDATED, 13, 4, "di.4@shift.dk", "Employee", "ACTIVE"),
                tombstone(13, 5));
    }

    /** Every permutation of the history must give the state of the in-order delivery, which is returned. */
    private Map<Long, Account> assertSameStateInEveryOrder(List<EventEnvelope> history) {
        Map<Long, Account> expected = deliver(history, new RememberingLog());
        for (List<EventEnvelope> order : permutations(history)) {
            assertThat(deliver(order, new RememberingLog())).as("order %s", describe(order)).isEqualTo(expected);
        }
        return expected;
    }

    /** A fresh projection per delivery order; the result is every stored account by employeeId. */
    private Map<Long, Account> deliver(List<EventEnvelope> order, ProcessedEventLog log) {
        InMemoryAccounts accounts = new InMemoryAccounts();
        AdminBootstrap noBootstrap = new AdminBootstrap(accounts, new PepperedPasswordEncoder("test-pepper"),
                null, null);
        EmployeeProjectionService projection = new EmployeeProjectionService(accounts, log, mapper, noBootstrap);
        order.forEach(projection::apply);

        return new TreeMap<>(accounts.byEmployeeId);
    }

    private EventEnvelope snapshot(String eventType, long employeeId, long version, String email, String role,
                                   String status) {
        ObjectNode data = mapper.createObjectNode()
                .put("employeeId", employeeId)
                .put("employeeNumber", "EMP-" + employeeId)
                .put("userRole", role)
                .put("firstName", "First" + version)
                .put("lastName", "Last" + version)
                .put("email", email)
                .put("employmentStatus", status);
        return new EventEnvelope(UUID.randomUUID(), eventType, OCCURRED_AT.plusSeconds(version),
                "workforce-service", employeeId, version, "corr-" + employeeId, null, data);
    }

    private EventEnvelope tombstone(long employeeId, long version) {
        ObjectNode data = mapper.createObjectNode()
                .put("entityType", "employee")
                .put("entityId", employeeId)
                .put("deletedAt", OCCURRED_AT.plusSeconds(version).toString());
        return new EventEnvelope(UUID.randomUUID(), DELETED, OCCURRED_AT.plusSeconds(version),
                "workforce-service", employeeId, version, "corr-" + employeeId, null, data);
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

    /** Behaves like the JPA adapter (upsert by employeeId), minus the generated accountId. */
    private static final class InMemoryAccounts implements AccountRepository {
        private final Map<Long, Account> byEmployeeId = new HashMap<>();

        @Override
        public Optional<Account> findByEmployeeId(long employeeId) {
            return Optional.ofNullable(byEmployeeId.get(employeeId));
        }

        @Override
        public Optional<Account> findByEmail(String email) {
            return byEmployeeId.values().stream()
                    .filter(a -> a.email() != null && a.email().equalsIgnoreCase(email)).findFirst();
        }

        @Override
        public Account save(Account account) {
            byEmployeeId.put(account.employeeId(), account);
            return account;
        }

        @Override
        public boolean setPasswordHash(long employeeId, String passwordHash) {
            Account account = byEmployeeId.get(employeeId);
            if (account == null) {
                return false;
            }
            byEmployeeId.put(employeeId, account.withPasswordHash(passwordHash));
            return true;
        }

        @Override
        public boolean setPasswordHashIfUnset(long employeeId, String passwordHash) {
            Account account = byEmployeeId.get(employeeId);
            return account != null && account.passwordHash() == null && setPasswordHash(employeeId, passwordHash);
        }
    }
}
