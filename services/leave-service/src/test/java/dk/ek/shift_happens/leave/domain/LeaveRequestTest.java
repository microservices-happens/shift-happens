package dk.ek.shift_happens.leave.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The leave request rules, without Spring. */
class LeaveRequestTest {

    private static final Instant NOW = Instant.parse("2027-01-01T10:00:00Z");

    private static LocalDate day(int dayOfMonth) {
        return LocalDate.of(2027, 3, dayOfMonth);
    }

    private static LeaveRequest saved(long id, long employeeId, int from, int to, LeaveRequestStatus status) {
        return new LeaveRequest(id, employeeId, 1, day(from), day(to), status, null, NOW, null, null, 1);
    }

    private static LeaveRequest pending() {
        return saved(10, 5, 1, 5, LeaveRequestStatus.PENDING);
    }

    private static LeaveDecision decision(ApprovalDecision verdict, String comment) {
        return new LeaveDecision(99, verdict, comment, NOW);
    }

    @Test
    void aSubmittedRequestIsPendingAtVersionOneWithTheGivenTime() {
        LeaveRequest request = LeaveRequest.submit(5, 1, day(1), day(5), "holiday", NOW);

        assertThat(request.status()).isEqualTo(LeaveRequestStatus.PENDING);
        assertThat(request.aggregateVersion()).isEqualTo(1);
        assertThat(request.requestedDatetime()).isEqualTo(NOW);
        assertThat(request.decision()).isNull();
        assertThat(request.rejectionReason()).isNull();
    }

    @Test
    void oneDayOfLeaveIsValidButAnEndBeforeTheStartIsNot() {
        assertThat(LeaveRequest.submit(5, 1, day(3), day(3), null, NOW).endDate()).isEqualTo(day(3));

        assertThatThrownBy(() -> LeaveRequest.submit(5, 1, day(5), day(4), null, NOW))
                .isInstanceOf(InvalidLeaveRequestException.class)
                .hasMessageContaining("before startDate");
    }

    @Test
    void missingDatesAreInvalid() {
        assertThatThrownBy(() -> LeaveRequest.submit(5, 1, null, day(4), null, NOW))
                .isInstanceOf(InvalidLeaveRequestException.class);
    }

    @Test
    void rangesSharingOnlyTheBoundaryDayOverlapBecauseTheEndDateIsTheLastDayOfLeave() {
        LeaveRequest request = pending(); // 1st-5th

        assertThat(request.overlaps(day(5), day(7))).isTrue();
        assertThat(request.overlaps(day(6), day(7))).isFalse();
        assertThat(request.overlaps(day(1), day(1))).isTrue();
        assertThat(request.overlaps(day(20), day(21))).isFalse();
        assertThat(request.overlaps(day(2), day(3))).as("contained").isTrue();
        assertThat(request.overlaps(day(1), day(10))).as("containing").isTrue();
    }

    @Test
    void anOverlapWithAnotherOccupyingRequestOfTheSameEmployeeIsRejected() {
        LeaveRequest candidate = LeaveRequest.submit(5, 1, day(5), day(7), null, NOW);

        for (LeaveRequestStatus occupying : List.of(LeaveRequestStatus.PENDING, LeaveRequestStatus.APPROVAL_PENDING,
                LeaveRequestStatus.APPROVED)) {
            List<LeaveRequest> others = List.of(saved(10, 5, 1, 5, occupying));
            assertThatThrownBy(() -> candidate.requireNoOverlapWith(others))
                    .isInstanceOf(LeaveRequestRejectedException.class)
                    .hasMessageContaining("already has leave request 10");
        }
    }

    @Test
    void touchingButNotSharingADayIsAllowed() {
        LeaveRequest candidate = LeaveRequest.submit(5, 1, day(6), day(7), null, NOW);

        candidate.requireNoOverlapWith(List.of(saved(10, 5, 1, 5, LeaveRequestStatus.PENDING)));
    }

    @Test
    void rejectedCancelledAndOtherEmployeesRequestsDoNotCount() {
        LeaveRequest candidate = LeaveRequest.submit(5, 1, day(1), day(5), null, NOW);

        candidate.requireNoOverlapWith(List.of(
                saved(10, 5, 1, 5, LeaveRequestStatus.REJECTED),
                saved(11, 5, 1, 5, LeaveRequestStatus.CANCELLED),
                saved(12, 6, 1, 5, LeaveRequestStatus.PENDING)));
    }

    @Test
    void aRequestNeverOverlapsItself() {
        LeaveRequest edited = pending().edited(null, day(2), day(6), null);

        edited.requireNoOverlapWith(List.of(pending()));
    }

    @Test
    void editingKeepsOmittedFieldsAndBumpsTheVersion() {
        LeaveRequest edited = pending().edited(2L, null, day(8), "new reason");

        assertThat(edited.leaveTypeId()).isEqualTo(2);
        assertThat(edited.startDate()).isEqualTo(day(1));
        assertThat(edited.endDate()).isEqualTo(day(8));
        assertThat(edited.reason()).isEqualTo("new reason");
        assertThat(edited.status()).isEqualTo(LeaveRequestStatus.PENDING);
        assertThat(edited.aggregateVersion()).isEqualTo(2);
        assertThat(edited.requestedDatetime()).isEqualTo(NOW);
    }

    @Test
    void anEditCannotProduceAnEndBeforeTheStart() {
        assertThatThrownBy(() -> pending().edited(null, day(9), null, null))
                .isInstanceOf(InvalidLeaveRequestException.class);
    }

    @Test
    void cancellingMovesToCancelledAndBumpsTheVersion() {
        LeaveRequest cancelled = pending().cancelled();

        assertThat(cancelled.status()).isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThat(cancelled.aggregateVersion()).isEqualTo(2);
    }

    @Test
    void cancellingACancelledRequestIsANoOp() {
        LeaveRequest cancelled = pending().cancelled();

        assertThat(cancelled.cancelled()).isSameAs(cancelled);
        assertThat(cancelled.cancelled().aggregateVersion()).isEqualTo(2);
    }

    @Test
    void aCancelledRequestCanStillNotBeEditedOrRejected() {
        LeaveRequest cancelled = pending().cancelled();

        assertThatThrownBy(() -> cancelled.edited(null, null, null, "x"))
                .isInstanceOf(LeaveRequestRejectedException.class).hasMessageContaining("CANCELLED");
        assertThatThrownBy(() -> cancelled.rejected(decision(ApprovalDecision.REJECTED, "no"), "default"))
                .isInstanceOf(LeaveRequestRejectedException.class);
    }

    @Test
    void anEditThatChangesNothingKeepsTheSameInstanceAndVersion() {
        LeaveRequest request = pending();

        assertThat(request.edited(null, null, null, null)).isSameAs(request);
        assertThat(request.edited(request.leaveTypeId(), request.startDate(), request.endDate(), request.reason()))
                .isSameAs(request);
        assertThat(request.edited(null, null, null, "another reason").aggregateVersion()).isEqualTo(2);
    }

    @Test
    void onlyPendingRequestsCanBeEditedCancelledOrRejected() {
        for (LeaveRequestStatus status : List.of(LeaveRequestStatus.APPROVAL_PENDING, LeaveRequestStatus.APPROVED,
                LeaveRequestStatus.REJECTED)) {
            LeaveRequest request = saved(10, 5, 1, 5, status);

            assertThatThrownBy(() -> request.edited(null, null, null, "x"))
                    .isInstanceOf(LeaveRequestRejectedException.class).hasMessageContaining(status.name());
            assertThatThrownBy(request::cancelled).isInstanceOf(LeaveRequestRejectedException.class);
            assertThatThrownBy(() -> request.rejected(decision(ApprovalDecision.REJECTED, "no"), "default"))
                    .isInstanceOf(LeaveRequestRejectedException.class);
        }
    }

    @Test
    void rejectingStoresTheDecisionAndUsesTheCommentAsTheReason() {
        LeaveDecision rejection = decision(ApprovalDecision.REJECTED, "Too busy");

        LeaveRequest rejected = pending().rejected(rejection, "default");

        assertThat(rejected.status()).isEqualTo(LeaveRequestStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("Too busy");
        assertThat(rejected.decision()).isEqualTo(rejection);
        assertThat(rejected.aggregateVersion()).isEqualTo(2);
    }

    @Test
    void aMissingOrBlankCommentFallsBackToTheDefaultReason() {
        assertThat(pending().rejected(decision(ApprovalDecision.REJECTED, null), "default").rejectionReason())
                .isEqualTo("default");
        assertThat(pending().rejected(decision(ApprovalDecision.REJECTED, "  "), "default").rejectionReason())
                .isEqualTo("default");
    }

    @Test
    void approvingIsNotAvailableYetAndChangesNothing() {
        LeaveRequest request = pending();

        assertThatThrownBy(() -> request.startApproval(decision(ApprovalDecision.APPROVED, "ok")))
                .isInstanceOf(ApprovalNotAvailableException.class)
                .hasMessageContaining("P3.1");
        assertThat(request.status()).isEqualTo(LeaveRequestStatus.PENDING);
        assertThat(request.aggregateVersion()).isEqualTo(1);
    }

    @Test
    void theSnapshotCarriesTheContractFields() {
        LeaveDecision rejection = decision(ApprovalDecision.REJECTED, "no");
        LeaveRequest rejected = pending().rejected(rejection, "default");

        LeaveRequestSnapshot snapshot = rejected.snapshot("Vacation");

        assertThat(snapshot.leaveRequestId()).isEqualTo(10);
        assertThat(snapshot.leaveTypeName()).isEqualTo("Vacation");
        assertThat(snapshot.requestStatus()).isEqualTo("REJECTED");
        assertThat(snapshot.decision()).isEqualTo(rejection);
        assertThat(snapshot.rejectionReason()).isEqualTo("no");
        assertThat(snapshot.requestedDatetime()).isEqualTo(NOW);
    }

    @Test
    void anUnsavedRequestHasNoSnapshot() {
        LeaveRequest unsaved = LeaveRequest.submit(5, 1, day(1), day(2), null, NOW);

        assertThatThrownBy(() -> unsaved.snapshot("Vacation")).isInstanceOf(IllegalStateException.class);
    }
}
