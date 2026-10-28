package dk.ek.shift_happens.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.ai.application.SuggestionService;
import dk.ek.shift_happens.ai.infrastructure.llm.OllamaClient;
import dk.ek.shift_happens.ai.infrastructure.persistence.SuggestionLogRetention;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:ai_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "management.health.rabbit.enabled=false"
})
class AiServiceApplicationTests {

    @Autowired
    private TestRestTemplate http;
    @Autowired private SuggestionService suggestions;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private SuggestionLogRetention retention;
    @MockitoBean private OllamaClient llm;

    // The gateway, Compose and Kubernetes probes route traffic based on GET /health
    // (contracts/README.md). If this path moves, the service silently drops out of rotation.
    @Test
    void healthEndpointIsPublicAndUp() {
        ResponseEntity<String> response = http.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void invalidModelAnswerFallsBackAndRedeliveryIsIgnored() throws Exception {
        when(llm.generate(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenAnswer(invocation -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return """
                            {"reason":"Invented", "rankedCandidates":[{"employeeId":999,"rank":1}]}
                            """;
                });
        var input = mapper.readTree("""
                {"eventId":"aaaaaaaa-aaaa-4aaa-aaaa-aaaaaaaaaaaa",
                 "eventType":"scheduling.shift.understaffed.v1", "aggregateVersion":2,
                 "correlationId":"test-correlation",
                 "data":{"shiftId":42,"jobRoleId":3,"candidates":[
                   {"employeeId":7,"hoursThisWeek":30,"weeklyContractHours":37},
                   {"employeeId":8,"hoursThisWeek":12,"weeklyContractHours":37}]}}
                """);
        suggestions.handle(input);
        suggestions.handle(input);
        verify(llm, times(1)).generate(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList());
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_event", String.class);
        var output = mapper.readTree(payload);
        assertThat(output.path("data").path("source").asText()).isEqualTo("rule");
        assertThat(output.path("data").path("reason").asText()).contains("fewest scheduled hours");
        assertThat(output.path("data").path("rankedCandidates").get(0).path("employeeId").asLong()).isEqualTo(8);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event", Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE suggestion_log SET created_at=?",
                Instant.now().minus(8, ChronoUnit.DAYS));
        retention.deleteExpiredLogs();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suggestion_log", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_event", Integer.class)).isEqualTo(1);
    }
}
