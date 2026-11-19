package dk.ek.shift_happens.scheduling.domain;

import java.util.Locale;

/**
 * Where a replacement ranking came from (contracts/events/payloads/replacement-suggestion.schema.json).
 *
 * <p>The event carries lowercase {@code llm|rule}; the GraphQL enum is uppercase, so the mapping lives
 * here and both sides stay in sync.
 */
public enum SuggestionSource {
    LLM,
    RULE;

    public static SuggestionSource of(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("a replacement suggestion must say whether it came from llm or rule");
        }
        try {
            return valueOf(source.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("unknown replacement suggestion source '" + source + "'");
        }
    }
}
