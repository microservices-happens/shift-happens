package dk.ek.shift_happens.scheduling.domain;

import java.time.Instant;
import java.util.List;

/**
 * data of ai.replacement.suggested.v1 as the read model keeps it: the latest ranking per shift and job
 * role, so the schedule can show it next to the understaffed shift.
 */
public record SuggestionView(
        long shiftId,
        long jobRoleId,
        SuggestionSource source,
        String model,
        Instant suggestedAt,
        String reason,
        List<RankedCandidate> candidates) {
}
