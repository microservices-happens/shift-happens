package dk.ek.shift_happens.leave.domain;

import dk.ek.shift_happens.leave.domain.LeaveApprovalSaga.Reaction;
import dk.ek.shift_happens.leave.domain.LeaveApprovalSaga.Reply;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a reply does to a saga attempt in each status, and the retry cap, without Spring. */
class LeaveApprovalSagaTest {

    private static final Instant NOW = Instant.parse("2027-01-01T10:00:00Z");

    private static LeaveApprovalSaga started(int attempt) {
        return LeaveApprovalSaga.start(7, attempt, UUID.randomUUID(), "corr", NOW, Duration.ofMinutes(5));
    }

    @Test
    void aNewAttemptIsStartedWithADeadlineOfNowPlusTheTimeout() {
        LeaveApprovalSaga saga = started(1);

        assertThat(saga.status()).isEqualTo(SagaStatus.STARTED);
        assertThat(saga.deadline()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(saga.isOverdue(NOW.plus(Duration.ofMinutes(5)))).as("deadline itself is not overdue").isFalse();
        assertThat(saga.isOverdue(NOW.plus(Duration.ofMinutes(5)).plusMillis(1))).isTrue();
    }

    @Test
    void aStartedAttemptCompletesOnCompletedAndCompensatesOnRejected() {
        assertThat(started(1).reactTo(Reply.COMPLETED, true)).isEqualTo(Reaction.COMPLETE);
        assertThat(started(1).reactTo(Reply.REJECTED, true)).isEqualTo(Reaction.COMPENSATE);
    }

    @Test
    void aLateCompletedAfterATimeoutRetriesButALateRejectedIsIgnored() {
        LeaveApprovalSaga timedOut = started(1).timedOut(NOW);

        assertThat(timedOut.reactTo(Reply.COMPLETED, true)).isEqualTo(Reaction.RETRY);
        assertThat(timedOut.reactTo(Reply.REJECTED, true)).isEqualTo(Reaction.IGNORE);
    }

    @Test
    void onlyTheNewestAttemptCanBeRetried() {
        assertThat(started(1).timedOut(NOW).reactTo(Reply.COMPLETED, false)).isEqualTo(Reaction.IGNORE);
    }

    @Test
    void theRetryIsCappedAtTheMaximumNumberOfAttempts() {
        assertThat(LeaveApprovalSaga.MAX_ATTEMPTS).isEqualTo(3);
        assertThat(started(2).timedOut(NOW).reactTo(Reply.COMPLETED, true)).isEqualTo(Reaction.RETRY);
        assertThat(started(3).timedOut(NOW).reactTo(Reply.COMPLETED, true)).isEqualTo(Reaction.IGNORE);
    }

    @Test
    void completedAndCompensatedAttemptsAreFinal() {
        for (LeaveApprovalSaga saga : new LeaveApprovalSaga[] {started(1).completed(NOW), started(1).compensated(NOW)}) {
            assertThat(saga.reactTo(Reply.COMPLETED, true)).as(saga.status().name()).isEqualTo(Reaction.IGNORE);
            assertThat(saga.reactTo(Reply.REJECTED, true)).as(saga.status().name()).isEqualTo(Reaction.IGNORE);
        }
    }

    @Test
    void onlyAStartedAttemptCanChangeStatus() {
        LeaveApprovalSaga done = started(1).completed(NOW);

        assertThatThrownBy(() -> done.timedOut(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> done.compensated(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> started(1).timedOut(NOW).completed(NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(done.isOverdue(NOW.plus(Duration.ofDays(1)))).as("only STARTED attempts time out").isFalse();
    }
}
