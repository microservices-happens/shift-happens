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
        LeaveRequest cancelled = pending().cancelled(day(1).minusDays(1));

        assertThat(cancelled.status()).isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThat(cancelled.aggregateVersion()).isEqualTo(2);
    }

    @Test
    void cancellingACancelledRequestIsANoOp() {
        LeaveRequest cancelled = pending().cancelled(day(1).minusDays(1));

        assertThat(cancelled.cancelled(day(1).minusDays(1))).isSameAs(cancelled);
        assertThat(cancelled.cancelled(day(1).minusDays(1)).aggregateVersion()).isEqualTo(2);
    }

    @Test
    void aCancelledRequestCanStillNotBeEditedOrRejected() {
        LeaveRequest cancelled = pending().cancelled(day(1).minusDays(1));

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
    void onlyPendingRequestsCanBeEditedOrRejected() {
        for (LeaveRequestStatus status : List.of(LeaveRequestStatus.APPROVAL_PENDING, LeaveRequestStatus.APPROVED,
                LeaveRequestStatus.REJECTED)) {
            LeaveRequest request = saved(10, 5, 1, 5, status);

            assertThatThrownBy(() -> request.edited(null, null, null, "x"))
                    .isInstanceOf(LeaveRequestRejectedException.class).hasMessageContaining(status.name());
            assertThatThrownBy(() -> request.rejected(decision(ApprovalDecision.REJECTED, "no"), "default"))
                    .isInstanceOf(LeaveRequestRejectedException.class);
        }
    }

    @Test
    void approvedLeaveCanOnlyBeCancelledBeforeItsFirstDay() {
        LeaveRequest approved = saved(10, 5, 10, 12, LeaveRequestStatus.APPROVED);

        LeaveRequest cancelled = approved.cancelled(day(10).minusDays(1));
        assertThat(cancelled.status()).isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThat(cancelled.aggregateVersion()).isEqualTo(approved.aggregateVersion() + 1);
        assertThatThrownBy(() -> approved.cancelled(day(10)))
                .isInstanceOf(LeaveRequestRejectedException.class).hasMessageContaining("first day");
        assertThatThrownBy(() -> approved.cancelled(day(10).plusDays(3)))
                .isInstanceOf(LeaveRequestRejectedException.class);
    }

    @Test
    void theStartDateRuleDoesNotApplyToPendingRequests() {
        LeaveRequest request = saved(10, 5, 10, 12, LeaveRequestStatus.PENDING);

        assertThat(request.cancelled(day(10).plusDays(10)).status()).isEqualTo(LeaveRequestStatus.CANCELLED);
    }

    @Test
    void onlyPendingAndApprovedRequestsCanBeCancelled() {
        assertThat(saved(10, 5, 1, 5, LeaveRequestStatus.APPROVED).cancelled(day(1).minusDays(1)).status())
                .isEqualTo(LeaveRequestStatus.CANCELLED);
        assertThatThrownBy(() -> saved(10, 5, 1, 5, LeaveRequestStatus.APPROVAL_PENDING).cancelled(day(1).minusDays(1)))
                .isInstanceOf(LeaveRequestRejectedException.class).hasMessageContaining("saga is in flight");
        assertThatThrownBy(() -> saved(10, 5, 1, 5, LeaveRequestStatus.REJECTED).cancelled(day(1).minusDays(1)))
                .isInstanceOf(LeaveRequestRejectedException.class).hasMessageContaining("REJECTED");
    }

    @Test
    void approvingMovesAPendingRequestToApprovalPendingWithTheDecision() {
        LeaveDecision approval = decision(ApprovalDecision.APPROVED, "ok");

        LeaveRequest started = pending().approvalStarted(approval);

        assertThat(started.status()).isEqualTo(LeaveRequestStatus.APPROVAL_PENDING);
        assertThat(started.aggregateVersion()).isEqualTo(2);
        assertThat(started.decision()).isEqualTo(approval);
        assertThat(started.rejectionReason()).isNull();
    }

    @Test
    void onlyAPendingRequestCanBeApproved() {
        for (LeaveRequestStatus status : List.of(LeaveRequestStatus.APPROVAL_PENDING, LeaveRequestStatus.APPROVED,
                LeaveRequestStatus.REJECTED, LeaveRequestStatus.CANCELLED)) {
            assertThatThrownBy(() -> saved(10, 5, 1, 5, status).approvalStarted(decision(ApprovalDecision.APPROVED, "ok")))
                    .as(status.name()).isInstanceOf(LeaveRequestRejectedException.class)
                    .hasMessageContaining(status.name());
        }
    }

    @Test
    void theSagaMovesApprovalPendingToApprovedOrRejectedAndKeepsTheManagersDecision() {
        LeaveDecision approval = decision(ApprovalDecision.APPROVED, "ok");
        LeaveRequest started = pending().approvalStarted(approval);

        LeaveRequest approved = started.approved();
        LeaveRequest rejected = started.rejectedBySaga("shift 3 is locked");

        assertThat(approved.status()).isEqualTo(LeaveRequestStatus.APPROVED);
        assertThat(approved.aggregateVersion()).isEqualTo(3);
        assertThat(rejected.status()).isEqualTo(LeaveRequestStatus.REJECTED);
        assertThat(rejected.rejectionReason()).isEqualTo("shift 3 is locked");
        assertThat(rejected.decision()).isEqualTo(approval);
    }

    @Test
    void theSagaStepsAreRefusedFromAnyOtherStatus() {
        for (LeaveRequestStatus status : List.of(LeaveRequestStatus.PENDING, LeaveRequestStatus.APPROVED,
                LeaveRequestStatus.REJECTED, LeaveRequestStatus.CANCELLED)) {
            LeaveRequest request = saved(10, 5, 1, 5, status);
            assertThatThrownBy(request::approved).as("approved from %s", status)
                    .isInstanceOf(LeaveRequestRejectedException.class);
            assertThatThrownBy(() -> request.rejectedBySaga("x")).as("rejectedBySaga from %s", status)
                    .isInstanceOf(LeaveRequestRejectedException.class);
        }
    }

    @Test
    void aRejectedRequestReturnsToApprovalPendingOnlyThroughTheRetry() {
        LeaveRequest rejected = pending().approvalStarted(decision(ApprovalDecision.APPROVED, "ok"))
                .rejectedBySaga("timeout");

        LeaveRequest retried = rejected.approvalRetried();

        assertThat(retried.status()).isEqualTo(LeaveRequestStatus.APPROVAL_PENDING);
        assertThat(retried.rejectionReason()).isNull();
        assertThat(retried.aggregateVersion()).isEqualTo(4);
        for (LeaveRequestStatus status : List.of(LeaveRequestStatus.PENDING, LeaveRequestStatus.APPROVAL_PENDING,
                LeaveRequestStatus.APPROVED, LeaveRequestStatus.CANCELLED)) {
            assertThatThrownBy(saved(10, 5, 1, 5, status)::approvalRetried).as(status.name())
                    .isInstanceOf(LeaveRequestRejectedException.class);
        }
        assertThatThrownBy(() -> rejected.approvalStarted(decision(ApprovalDecision.APPROVED, "again")))
                .isInstanceOf(LeaveRequestRejectedException.class);
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

    @Test
    void daysAreCalendarDaysInclusiveOfBothEnds() {
        assertThat(saved(1, 5, 3, 3, LeaveRequestStatus.PENDING).days()).as("single day").isEqualTo(1);
        assertThat(saved(1, 5, 1, 5, LeaveRequestStatus.PENDING).days()).as("1st to 5th").isEqualTo(5);
        // 2027-03-05 is a Friday and 2027-03-08 a Monday: the weekend counts, so 4 days
        assertThat(saved(1, 5, 5, 8, LeaveRequestStatus.PENDING).days()).as("across a weekend").isEqualTo(4);
        assertThat(saved(1, 5, 1, 31, LeaveRequestStatus.PENDING).days()).as("a whole month").isEqualTo(31);
    }
}
