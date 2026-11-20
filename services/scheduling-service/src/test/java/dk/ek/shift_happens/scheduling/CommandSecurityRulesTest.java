package dk.ek.shift_happens.scheduling;

import dk.ek.shift_happens.scheduling.api.ShiftController;
import dk.ek.shift_happens.scheduling.api.ShiftSwapController;
import dk.ek.shift_happens.scheduling.application.IdempotencyService;
import dk.ek.shift_happens.scheduling.application.ShiftService;
import dk.ek.shift_happens.scheduling.application.ShiftSwapService;
import dk.ek.shift_happens.scheduling.config.CommandSecurityConfig;
import dk.ek.shift_happens.scheduling.config.JwtConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security baseline of the command role (P1.7) without containers: the real filter chain and the
 * real JWT decoder (signature checked against the JWKS endpoint of {@link TestJwks}), use cases mocked.
 *
 * <p>The 401/403 rules for every endpoint and caller, including the Employee who requests a swap for
 * somebody else, are in {@link CommandSecurityMatrixTest}, derived from contracts/openapi/scheduling.yaml
 * (P6.6). This class keeps what the matrix does not assert: which use case an allowed write reaches, a
 * Manager acting for another employee, and that the JWKS is fetched once and then cached.
 */
@WebMvcTest({ShiftController.class, ShiftSwapController.class})
@ActiveProfiles("command")
@Import({CommandSecurityConfig.class, JwtConfig.class})
class CommandSecurityRulesTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ShiftService shifts;
    @MockitoBean
    private ShiftSwapService swaps;
    @MockitoBean
    private IdempotencyService idempotency;

    @Test
    void aManagerAndAnAdministratorMayWrite() throws Exception {
        mvc.perform(as(delete("/v1/shifts/1"), TestJwks.manager())).andExpect(status().isAccepted());
        mvc.perform(as(delete("/v1/shifts/2"), TestJwks.administrator())).andExpect(status().isAccepted());

        verify(shifts).delete(1, null);
        verify(shifts).delete(2, null);
    }

    @Test
    void anEmployeeMayRequestASwapForTheirOwnAssignment() throws Exception {
        mvc.perform(as(swapRequest(7), TestJwks.employee(7))).andExpect(status().isOk());

        // The mocked idempotency wrapper answers null (an empty 200); reaching it is what is asserted.
        verify(idempotency).execute(anyString(), any(), any());
    }

    @Test
    void aManagerAndAnAdministratorMayRequestASwapForAnyone() throws Exception {
        mvc.perform(as(swapRequest(8), TestJwks.manager())).andExpect(status().isOk());
        mvc.perform(as(swapRequest(8), TestJwks.administrator())).andExpect(status().isOk());

        verify(idempotency, times(2)).execute(anyString(), any(), any());
    }

    @Test
    void theJwksIsFetchedOnceAndThenCached() throws Exception {
        mvc.perform(as(delete("/v1/shifts/1"), TestJwks.manager())).andExpect(status().isAccepted());
        int afterTheFirstToken = TestJwks.fetches();

        for (int i = 2; i < 7; i++) {
            mvc.perform(as(delete("/v1/shifts/" + i), TestJwks.manager())).andExpect(status().isAccepted());
        }

        assertThat(afterTheFirstToken).isPositive();
        assertThat(TestJwks.fetches()).isEqualTo(afterTheFirstToken);
    }

    private static MockHttpServletRequestBuilder swapRequest(long employeeFromId) {
        return json(post("/v1/shiftswaps"), swapFrom(employeeFromId))
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private static String swapFrom(long employeeFromId) {
        return """
                {"originalShiftAssignmentId":1,"employeeFromId":%d,"employeeToId":9,"reason":"holiday"}
                """.formatted(employeeFromId);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
