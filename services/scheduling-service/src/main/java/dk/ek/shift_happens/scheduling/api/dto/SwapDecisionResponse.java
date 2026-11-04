package dk.ek.shift_happens.scheduling.api.dto;

import dk.ek.shift_happens.scheduling.domain.SwapDecision;

import java.time.Instant;

/** The manager's decision inside a swap response; null while the swap is pending. */
public record SwapDecisionResponse(
        long approverEmployeeId,
        String decision,
        String comment,
        Instant decisionDatetime) {

    public static SwapDecisionResponse from(SwapDecision decision) {
        return decision == null ? null : new SwapDecisionResponse(decision.approverEmployeeId(),
                decision.decision().name(), decision.comment(), decision.decisionDatetime());
    }
}
