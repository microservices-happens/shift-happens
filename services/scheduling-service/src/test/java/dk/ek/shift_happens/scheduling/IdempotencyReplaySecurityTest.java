package dk.ek.shift_happens.scheduling;

import dk.ek.shift_happens.scheduling.api.ShiftSwapController;
import dk.ek.shift_happens.scheduling.application.IdempotencyService;
import dk.ek.shift_happens.scheduling.application.IdempotencyStore;
import dk.ek.shift_happens.scheduling.application.ShiftSwapService;
import dk.ek.shift_happens.scheduling.config.CommandSecurityConfig;
import dk.ek.shift_happens.scheduling.config.JwtConfig;
import dk.ek.shift_happens.scheduling.domain.ShiftSwap;
import dk.ek.shift_happens.scheduling.domain.SwapStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * P6.6 "idempotency replay": an {@code Idempotency-Key} replay cannot be used to get past the role rules.
 *
 * <p>The key is not scoped to a caller (contracts/README.md: a repeated key returns the stored first
 * response), so what protects the stored response is that authorization runs first: the filter chain for
 * the role, and {@link ShiftSwapController} for the Employee's own-assignment rule, before the idempotency
 * lookup. The real {@link IdempotencyService} runs here over an in-memory store; only the swap use case is
 * mocked.
 */
@WebMvcTest(ShiftSwapController.class)
@ActiveProfiles("command")
@Import({CommandSecurityConfig.class, JwtConfig.class, IdempotencyService.class,
        IdempotencyReplaySecurityTest.InMemoryStore.class})
class IdempotencyReplaySecurityTest {

    private static final String SWAP_FROM_7 = """
            {"originalShiftAssignmentId":1,"employeeFromId":7,"employeeToId":9,"reason":"holiday"}
            """;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ShiftSwapService swaps;
    /** A fresh key per test: the store outlives a test, as the real one does. */
    private final String key = UUID.randomUUID().toString();

    @BeforeEach
    void employee7RequestedASwap() throws Exception {
        when(swaps.request(anyLong(), anyLong(), anyLong(), any(), any())).thenReturn(new ShiftSwap(
                41L, 1, 3, 7, 9, SwapStatus.PENDING, "holiday", Instant.parse("2026-11-02T06:00:00Z"), null, 1));
        mvc.perform(swap(TestJwks.employee(7)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.shiftSwapId").value(41));
    }

    @Test
    void anotherEmployeeReplayingTheSameKeyAndBodyIsForbiddenAndGetsNothingStored() throws Exception {
        mvc.perform(swap(TestJwks.employee(8)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(not(containsString("shiftSwapId"))));

        verify(swaps, times(1)).request(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void aReplayWithoutAValidTokenIsA401AndGetsNothingStored() throws Exception {
        mvc.perform(swap(null))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("shiftSwapId"))));
        mvc.perform(swap(TestJwks.expiredToken(7, "Employee")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("shiftSwapId"))));
        mvc.perform(swap(TestJwks.tokenSignedByAnotherKey(7, "Employee")))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("shiftSwapId"))));
    }

    @Test
    void aCallerWhoMayMakeTheRequestGetsTheStoredResponseWithoutASecondSideEffect() throws Exception {
        // The specified behaviour: the key is not per caller, so a Manager (who may request this swap
        // anyway) replaying it gets the first response, and the use case still ran once.
        mvc.perform(swap(TestJwks.manager()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.shiftSwapId").value(41));

        verify(swaps, times(1)).request(anyLong(), anyLong(), anyLong(), any(), any());
    }

    @Test
    void theOwnerReplayingTheKeyWithAnotherBodyIsAConflict() throws Exception {
        mvc.perform(swap(TestJwks.employee(7)).content(SWAP_FROM_7.replace("holiday", "sick")))
                .andExpect(status().isConflict());

        verify(swaps, times(1)).request(anyLong(), anyLong(), anyLong(), any(), any());
    }

    private MockHttpServletRequestBuilder swap(String token) {
        MockHttpServletRequestBuilder request = post("/v1/shiftswaps")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(SWAP_FROM_7);
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    /** The JDBC store's behaviour without a database. */
    @TestConfiguration
    static class InMemoryStore {

        @Bean
        IdempotencyStore idempotencyStore() {
            Map<String, IdempotencyStore.StoredResponse> stored = new ConcurrentHashMap<>();
            return new IdempotencyStore() {
                @Override
                public Optional<StoredResponse> find(String idempotencyKey) {
                    return Optional.ofNullable(stored.get(idempotencyKey));
                }

                @Override
                public void save(String idempotencyKey, String requestHash, int responseStatus, String responseBody) {
                    stored.putIfAbsent(idempotencyKey, new StoredResponse(requestHash, responseStatus, responseBody));
                }
            };
        }
    }
}
