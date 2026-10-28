package dk.ek.shift_happens.ai.infrastructure.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SuggestionPersistence {
    private final JdbcTemplate jdbc;

    public SuggestionPersistence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Commit deduplication, review log and outgoing event together after inference has finished. */
    @Transactional
    public void saveIfNew(UUID eventId, long shiftId, String prompt, String modelResponse,
                          String source, UUID outputId, String outputPayload) {
        int inserted = jdbc.update("INSERT INTO processed_event(event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId);
        if (inserted == 0) return;
        jdbc.update("INSERT INTO suggestion_log(event_id,shift_id,prompt,model_response,source) VALUES (?,?,?,?,?)",
                eventId, shiftId, prompt, modelResponse, source);
        jdbc.update("INSERT INTO outbox_event(event_id,event_type,payload) VALUES (?,?,?)",
                outputId, "ai.replacement.suggested.v1", outputPayload);
    }
}
