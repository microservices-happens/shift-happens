package dk.ek.shift_happens.scheduling.application;

import java.util.List;

/**
 * data of ai.replacement.suggested.v1 as it arrives (contracts/events/payloads/replacement-suggestion.schema.json).
 *
 * <p>Kept next to the projection instead of in domain/ because it is an inbound shape only: the read
 * model stores {@code SuggestionView}, which has the timestamp the envelope provides.
 */
record ReplacementSuggestionPayload(
        long shiftId,
        long jobRoleId,
        List<Ranked> rankedCandidates,
        String reason,
        String source,
        String model) {

    /** One ranked candidate of the AI answer; only employeeId and rank are trusted. */
    record Ranked(long employeeId, int rank) {
    }
}
