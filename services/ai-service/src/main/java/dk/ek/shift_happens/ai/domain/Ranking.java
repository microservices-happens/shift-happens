package dk.ek.shift_happens.ai.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class Ranking {
    private Ranking() {}

    public record Candidate(long employeeId, double hoursThisWeek, double weeklyContractHours) {}
    public record Ranked(long employeeId, int rank) {}

    public static String validateReason(JsonNode response) {
        JsonNode reason = response.path("reason");
        if (!reason.isTextual()) throw new IllegalArgumentException("Missing overall reason");
        String value = reason.asText().trim();
        if (value.isEmpty() || value.split("\\s+").length > 100)
            throw new IllegalArgumentException("Overall reason must contain 1-100 words");
        return value;
    }

    public static List<Ranked> validate(JsonNode response, List<Candidate> candidates) {
        JsonNode rows = response.path("rankedCandidates");
        if (!rows.isArray() || rows.size() != candidates.size()) throw new IllegalArgumentException("Incomplete ranking");
        Set<Long> allowed = new HashSet<>();
        for (Candidate candidate : candidates) allowed.add(candidate.employeeId());
        Set<Long> seen = new HashSet<>();
        Set<Integer> ranks = new HashSet<>();
        List<Ranked> result = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode id = row.path("employeeId");
            JsonNode rank = row.path("rank");
            if (!id.isIntegralNumber() || !rank.isIntegralNumber() || !allowed.contains(id.asLong())
                    || !seen.add(id.asLong()) || !ranks.add(rank.asInt())) {
                throw new IllegalArgumentException("Invalid model ranking");
            }
            result.add(new Ranked(id.asLong(), rank.asInt()));
        }
        for (int i = 1; i <= result.size(); i++) {
            if (!ranks.contains(i)) throw new IllegalArgumentException("Non-contiguous ranks");
        }
        return result.stream().sorted(Comparator.comparingInt(Ranked::rank)).toList();
    }

    public static List<Ranked> fallback(List<Candidate> candidates) {
        List<Candidate> sorted = candidates.stream()
                .sorted(Comparator.comparingDouble(Candidate::hoursThisWeek).thenComparingLong(Candidate::employeeId))
                .toList();
        List<Ranked> result = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            result.add(new Ranked(sorted.get(i).employeeId(), i + 1));
        }
        return result;
    }
}
