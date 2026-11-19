package dk.ek.shift_happens.leave.application;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.leave.domain.LeaveRequestSnapshot;
import dk.ek.shift_happens.leave.domain.LeaveTypeSnapshot;
import dk.ek.shift_happens.leave.domain.LedgerEntryRecorded;
import dk.ek.shift_happens.leave.domain.ReadModelWriter;
import dk.ek.shift_happens.leave.infrastructure.messaging.ProjectionEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the projection does with an envelope, against a recording fake of the store (no Spring, no Mongo). */
class ProjectionServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
    private final RecordingStore store = new RecordingStore();
    private final ProjectionService projection = new ProjectionService(store, mapper);

    @Test
    void anEmployeeSnapshotKeepsTheNameAndNothingElse() throws Exception {
        projection.handle(envelope(ProjectionService.EMPLOYEE_UPDATED, 10, 3, """
                {"employeeId": 10, "firstName": "Grace", "lastName": "Hopper", "salary": 99999.5,
                 "email": "grace@shift.dk", "employeeNumber": "E-10"}"""));

        assertThat(store.calls).containsExactly("employee 10 Grace Hopper v3", "processed");
    }

    @Test
    void aTombstoneCarriesTheEntityIdTheVersionAndTheDeletionTime() throws Exception {
        projection.handle(envelope(ProjectionService.EMPLOYEE_DELETED, 10, 4, """
                {"entityType": "employee", "entityId": 10, "deletedAt": "2027-01-01T10:00:00Z"}"""));
        projection.handle(envelope(ProjectionService.TYPE_DELETED, 5, 2, """
                {"entityType": "leaveType", "entityId": 5, "deletedAt": "2027-01-02T10:00:00Z"}"""));

        assertThat(store.calls).containsExactly("employee-tombstone 10 v4 2027-01-01T10:00:00Z", "processed",
                "type-tombstone 5 v2 2027-01-02T10:00:00Z", "processed");
    }

    @Test
    void theChangeIsAppliedBeforeTheEventIsRecordedSoACrashBetweenThemOnlyMeansAReapply() throws Exception {
        store.failOnApply = true;
        ProjectionEnvelope envelope = envelope(ProjectionService.LEDGER_ENTRY_RECORDED, 1, 1, ledger(1, "1.50"));

        assertThatThrownBy(() -> projection.handle(envelope)).isInstanceOf(IllegalStateException.class);

        assertThat(store.processed).as("not recorded when the change failed, so the retry applies it").isEmpty();
    }

    @Test
    void anAlreadyProcessedEventIsSkippedWithoutLookingAtItsPayload() throws Exception {
        ProjectionEnvelope first = envelope(ProjectionService.LEDGER_ENTRY_RECORDED, 1, 1, ledger(1, "1.50"));
        projection.handle(first);
        store.calls.clear();

        projection.handle(new ProjectionEnvelope(first.eventId(), first.eventType(), first.occurredAt(),
                first.producer(), 1, 1, "c", null, mapper.readTree(ledger(1, "99"))));

        assertThat(store.calls).isEmpty();
    }

    @Test
    void anEventTypeThatIsNotHandledIsRecordedAndIgnored() throws Exception {
        ProjectionEnvelope envelope = envelope("workforce.employee.renamed.v1", 10, 1, "{\"employeeId\": 10}");

        projection.handle(envelope);

        assertThat(store.calls).containsExactly("processed");
        assertThat(store.processed).contains(envelope.eventId());
    }

    @Test
    void aLedgerEntryKeepsItsDecimalsExactly() throws Exception {
        projection.handle(envelope(ProjectionService.LEDGER_ENTRY_RECORDED, 7, 1, ledger(7, "-0.10")));

        LedgerEntryRecorded entry = store.ledgerEntries.get(0);
        assertThat(entry.leaveLedgerId()).isEqualTo(7);
        // Exact decimals, not doubles (0.1 + 0.2 style drift); Jackson may drop trailing zeros, the value is the same.
        assertThat(entry.changeAmountDays()).isEqualByComparingTo(new java.math.BigDecimal("-0.10"));
        assertThat(entry.balanceAfterDays()).isEqualByComparingTo(new java.math.BigDecimal("4.90"));
    }

    @Test
    void aLeaveRequestSnapshotCarriesTheDecisionWithItsApprovalId() throws Exception {
        projection.handle(envelope(ProjectionService.REQUEST_REJECTED, 20, 2, """
                {"leaveRequestId": 20, "employeeId": 10, "leaveTypeId": 5, "leaveTypeName": "Vacation",
                 "startDate": "2027-05-03", "endDate": "2027-05-04", "requestStatus": "REJECTED",
                 "requestedDatetime": "2027-01-01T10:00:00Z", "reason": null, "rejectionReason": "no",
                 "decision": {"leaveApprovalId": 9, "approverEmployeeId": 99, "decision": "REJECTED",
                              "comment": "no", "decisionDatetime": "2027-01-02T10:00:00Z"}}"""));

        LeaveRequestSnapshot snapshot = store.requests.get(0);
        assertThat(snapshot.decision().leaveApprovalId()).isEqualTo(9L);
        assertThat(snapshot.requestStatus()).isEqualTo("REJECTED");
        assertThat(store.calls).containsExactly("request 20 v2", "processed");
    }

    @Test
    void aSnapshotWithoutADecisionOrApprovalIdIsStillAccepted() throws Exception {
        projection.handle(envelope(ProjectionService.REQUEST_SUBMITTED, 21, 1, """
                {"leaveRequestId": 21, "employeeId": 10, "leaveTypeId": 5, "leaveTypeName": "Vacation",
                 "startDate": "2027-05-03", "endDate": "2027-05-04", "requestStatus": "PENDING",
                 "requestedDatetime": "2027-01-01T10:00:00Z", "decision": null}"""));
        projection.handle(envelope(ProjectionService.REQUEST_APPROVED, 21, 3, """
                {"leaveRequestId": 21, "employeeId": 10, "leaveTypeId": 5, "leaveTypeName": "Vacation",
                 "startDate": "2027-05-03", "endDate": "2027-05-04", "requestStatus": "APPROVED",
                 "requestedDatetime": "2027-01-01T10:00:00Z",
                 "decision": {"approverEmployeeId": 99, "decision": "APPROVED", "decisionDatetime": "2027-01-02T10:00:00Z"}}"""));

        assertThat(store.requests.get(0).decision()).isNull();
        assertThat(store.requests.get(1).decision().leaveApprovalId()).isNull();
    }

    @Test
    void aLeaveTypeSnapshotIsStoredWithItsVersion() throws Exception {
        projection.handle(envelope(ProjectionService.TYPE_CREATED, 5, 1, """
                {"leaveTypeId": 5, "leaveTypeName": "Vacation", "leaveTypeDescription": null,
                 "requiresApproval": true, "isPaidLeave": false}"""));

        assertThat(store.types).containsExactly(new LeaveTypeSnapshot(5, "Vacation", null, true, false));
        assertThat(store.calls).containsExactly("type 5 v1", "processed");
    }

    @Test
    void payloadsThatCanNeverBeAppliedAreRejectedAndNotRecorded() throws Exception {
        for (ProjectionEnvelope poison : List.of(
                envelope(ProjectionService.EMPLOYEE_CREATED, 1, 1, "{\"firstName\": \"Nobody\"}"),
                envelope(ProjectionService.EMPLOYEE_DELETED, 1, 1, "{\"entityType\": \"employee\"}"),
                envelope(ProjectionService.LEDGER_ENTRY_RECORDED, 1, 1, "{\"leaveLedgerId\": 1, \"employeeId\": 1,"
                        + " \"leaveTypeId\": 1}"),
                envelope(ProjectionService.LEDGER_ENTRY_RECORDED, 1, 1, "{\"employeeId\": 1, \"leaveTypeId\": 1, "
                        + "\"changeAmountDays\": 1}"),
                envelope(ProjectionService.REQUEST_SUBMITTED, 1, 1, "{\"leaveRequestId\": 1, \"employeeId\": 1, "
                        + "\"leaveTypeId\": 1, \"startDate\": \"2027-01-01\", \"endDate\": \"2027-01-02\", "
                        + "\"requestStatus\": \"SOMETHING\"}"),
                envelope(ProjectionService.TYPE_CREATED, 1, 1, "{\"leaveTypeId\": 1}"),
                new ProjectionEnvelope(UUID.randomUUID(), ProjectionService.REQUEST_SUBMITTED, Instant.now(),
                        "leave-service", 1, 1, "c", null, null))) {
            assertThatThrownBy(() -> projection.handle(poison))
                    .as(poison.eventType() + " " + poison.data())
                    .isInstanceOf(RuntimeException.class);
        }

        assertThat(store.processed).isEmpty();
        assertThat(store.calls).isEmpty();
    }

    private ProjectionEnvelope envelope(String eventType, long aggregateId, long version, String json)
            throws Exception {
        JsonNode data = mapper.readTree(json);
        return new ProjectionEnvelope(UUID.randomUUID(), eventType, Instant.parse("2027-01-01T09:00:00Z"),
                "test", aggregateId, version, "corr", null, data);
    }

    private static String ledger(long id, String amount) {
        return "{\"leaveLedgerId\": " + id + ", \"employeeId\": 10, \"leaveTypeId\": 5, \"changeAmountDays\": "
                + amount + ", \"balanceAfterDays\": 4.90, \"transactionType\": \"RESERVATION\", "
                + "\"referenceEntityType\": \"LEAVE_REQUEST\", \"referenceEntityId\": 20, "
                + "\"transactionDatetime\": \"2027-01-01T10:00:00Z\"}";
    }

    /** Records what the projection asked the store to do; the guards themselves are tested against real Mongo. */
    private static final class RecordingStore implements ReadModelWriter {
        final List<String> calls = new ArrayList<>();
        final Set<UUID> processed = new HashSet<>();
        final List<LeaveRequestSnapshot> requests = new ArrayList<>();
        final List<LeaveTypeSnapshot> types = new ArrayList<>();
        final List<LedgerEntryRecorded> ledgerEntries = new ArrayList<>();
        boolean failOnApply;

        @Override
        public boolean isProcessed(UUID eventId) {
            return processed.contains(eventId);
        }

        @Override
        public void markProcessed(UUID eventId) {
            processed.add(eventId);
            calls.add("processed");
        }

        @Override
        public void applyEmployee(long employeeId, String firstName, String lastName, long version) {
            calls.add("employee " + employeeId + " " + firstName + " " + lastName + " v" + version);
        }

        @Override
        public void tombstoneEmployee(long employeeId, long version, Instant deletedAt) {
            calls.add("employee-tombstone " + employeeId + " v" + version + " " + deletedAt);
        }

        @Override
        public void applyLeaveType(LeaveTypeSnapshot snapshot, long version) {
            types.add(snapshot);
            calls.add("type " + snapshot.leaveTypeId() + " v" + version);
        }

        @Override
        public void tombstoneLeaveType(long leaveTypeId, long version, Instant deletedAt) {
            calls.add("type-tombstone " + leaveTypeId + " v" + version + " " + deletedAt);
        }

        @Override
        public void applyLeaveRequest(LeaveRequestSnapshot snapshot, long version) {
            requests.add(snapshot);
            calls.add("request " + snapshot.leaveRequestId() + " v" + version);
        }

        @Override
        public void recordLedgerEntry(LedgerEntryRecorded entry) {
            if (failOnApply) {
                throw new IllegalStateException("mongo is down");
            }
            ledgerEntries.add(entry);
            calls.add("ledger " + entry.leaveLedgerId());
        }
    }
}
