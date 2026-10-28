package dk.ek.shift_happens.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.ai.domain.Ranking;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RankingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Ranking.Candidate> candidates = List.of(
            new Ranking.Candidate(7, 30, 37), new Ranking.Candidate(8, 12, 37));

    @Test void rejectsInventedCandidate() throws Exception {
        var response = mapper.readTree("""
                {"reason":"Overall order", "rankedCandidates":[{"employeeId":7,"rank":1},
                {"employeeId":99,"rank":2}]}
                """);
        assertThatThrownBy(() -> Ranking.validate(response, candidates))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void fallbackUsesFewestHoursWithStableTieBreak() {
        assertThat(Ranking.fallback(candidates)).extracting(Ranking.Ranked::employeeId)
                .containsExactly(8L, 7L);
    }

    @Test void acceptsCompleteRanking() throws Exception {
        var response = mapper.readTree("""
                {"reason":"Overall order", "rankedCandidates":[{"employeeId":8,"rank":2},
                {"employeeId":7,"rank":1}]}
                """);
        assertThat(Ranking.validate(response, candidates)).extracting(Ranking.Ranked::employeeId)
                .containsExactly(7L, 8L);
        assertThat(Ranking.validateReason(response)).isEqualTo("Overall order");
    }

    @Test void rejectsMoreThanOneHundredWords() throws Exception {
        String reason = "word ".repeat(101);
        var response = mapper.readTree(mapper.writeValueAsString(java.util.Map.of("reason", reason)));
        assertThatThrownBy(() -> Ranking.validateReason(response)).isInstanceOf(IllegalArgumentException.class);
    }
}
