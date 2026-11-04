package dk.ek.shift_happens.scheduling.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Swap rules: who may ask, when it can be decided, and what the handover does to the assignment. */
class SwapsTest {

    private static final Instant START = Instant.parse("2026-11-02T06:00:00Z");
    private static final Instant END = Instant.parse("2026-11-02T14:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-01T09:00:00Z");

    @Test
    void aRequestIsPendingWithoutADecision() {
        ShiftSwap swap = swap();

        assertThat(swap.status()).isEqualTo(SwapStatus.PENDING);
        assertThat(swap.decision()).isNull();
        assertThat(swap.aggregateVersion()).isEqualTo(1);
        assertThat(savedSwap().snapshot().decision()).isNull();
    }

    @Test
    void onlyTheHolderCanAskForASwap() {
        assertThatThrownBy(() -> ShiftSwap.request(assignment(), 8L, 9L, "holiday", NOW))
                .isInstanceOf(SwapRejectedException.class)
                .hasMessageContaining("belongs to employee 7, not 8");
    }

    @Test
    void anEmployeeCannotSwapWithThemselves() {
        assertThatThrownBy(() -> ShiftSwap.request(assignment(), 7L, 7L, "holiday", NOW))
                .isInstanceOf(SwapRejectedException.class)
                .hasMessageContaining("cannot swap a shift with themselves");
    }

    @Test
    void aCancelledAssignmentCannotBeSwapped() {
        ShiftAssignment cancelled = assignment().withStatus(AssignmentStatus.CANCELLED, null, null);

        assertThatThrownBy(() -> ShiftSwap.request(cancelled, 7L, 8L, "holiday", NOW))
                .isInstanceOf(SwapRejectedException.class)
                .hasMessageContaining("cannot be swapped");
    }

    @Test
    void aDecisionClosesTheSwapAndBumpsTheVersion() {
        ShiftSwap approved = savedSwap().decided(new SwapDecision(1L, ApprovalDecision.APPROVED, "ok", NOW));

        assertThat(approved.status()).isEqualTo(SwapStatus.APPROVED);
        assertThat(approved.aggregateVersion()).isEqualTo(2);
        assertThat(approved.snapshot().decision().decision()).isEqualTo(ApprovalDecision.APPROVED);
        assertThat(approved.snapshot().swapStatus()).isEqualTo("APPROVED");
    }

    @Test
    void aDecidedSwapCannotBeDecidedAgainOrChanged() {
        ShiftSwap rejected = swap().decided(new SwapDecision(1L, ApprovalDecision.REJECTED, "no", NOW));

        assertThatThrownBy(() -> rejected.decided(new SwapDecision(1L, ApprovalDecision.APPROVED, "ok", NOW)))
                .isInstanceOf(SwapRejectedException.class)
                .hasMessageContaining("is already REJECTED");
        assertThatThrownBy(() -> rejected.reRequested(9L, "other"))
                .isInstanceOf(SwapRejectedException.class);
    }

    @Test
    void aPendingSwapCanPointAtAnotherColleague() {
        ShiftSwap changed = swap().reRequested(9L, "other reason");

        assertThat(changed.employeeToId()).isEqualTo(9L);
        assertThat(changed.reason()).isEqualTo("other reason");
        assertThat(changed.aggregateVersion()).isEqualTo(2);
    }

    @Test
    void theHandoverKeepsTheRowAndMarksItSwapped() {
        ShiftAssignment moved = assignment().swappedTo(8L);

        assertThat(moved.shiftAssignmentId()).isEqualTo(5L);
        assertThat(moved.employeeId()).isEqualTo(8L);
        assertThat(moved.status()).isEqualTo(AssignmentStatus.SWAPPED);
        assertThat(moved.aggregateVersion()).isEqualTo(2);
    }

    /** A swap as it looks after it was stored: the snapshot needs the id the database assigned. */
    private static ShiftSwap savedSwap() {
        return new ShiftSwap(11L, 5L, 42L, 7L, 8L, SwapStatus.PENDING, "holiday", NOW, null, 1);
    }

    private static ShiftSwap swap() {        return ShiftSwap.request(assignment(), 7L, 8L, "holiday", NOW);
    }

    private static ShiftAssignment assignment() {
        return new ShiftAssignment(5L, 42L, 7L, AssignmentStatus.ASSIGNED, START, null, null, 1, false);
    }
}
