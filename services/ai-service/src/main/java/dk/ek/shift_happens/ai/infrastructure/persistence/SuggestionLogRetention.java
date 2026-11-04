package dk.ek.shift_happens.ai.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SuggestionLogRetention {
    private final JdbcTemplate jdbc;

    public SuggestionLogRetention(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void deleteExpiredLogs() {
        jdbc.update("DELETE FROM suggestion_log WHERE created_at < ?",
                OffsetDateTime.now(ZoneOffset.UTC).minus(7, ChronoUnit.DAYS));
    }
}
