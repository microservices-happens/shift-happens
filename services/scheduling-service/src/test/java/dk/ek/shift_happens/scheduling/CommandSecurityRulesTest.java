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
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security baseline of the command role (P1.7) without containers: the real filter chain and the
 * real JWT decoder (signature checked against the JWKS endpoint of {@link TestJwks}), use cases mocked.
 *
 * <p>Rules under test, from contracts/README.md and contracts/openapi/scheduling.yaml: no or an invalid
 * token is a 401; writes need Administrator or Manager (otherwise 403); an Employee may only
 * {@code POST /v1/shiftswaps} for their own assignment (employeeFromId = JWT employeeId).
 */
@WebMvcTest({ShiftController.class, ShiftSwapController.class})
@ActiveProfiles("command")
@Import({CommandSecurityConfig.class, JwtConfig.class})
class CommandSecurityRulesTest {

    private static final String SHIFT = """
            {"departmentId":2,"workLocationId":3,"shiftName":"Morning","startDatetime":"2026-11-02T06:00:00Z",
             "endDatetime":"2026-11-02T14:00:00Z","shiftStatus":"PLANNED",
             "requiredJobRoles":[{"jobRoleId":7,"requiredEmployeeCount":1}]}
            """;

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
    void aRequestWithoutATokenIsA401ProblemAndReachesNoUseCase() throws Exception {
        mvc.perform(json(post("/v1/shifts"), SHIFT))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(delete("/v1/shifts/1")).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/v1/shiftswaps"), swapFrom(7))).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/shifts")).andExpect(status().isUnauthorized());

        verifyNoInteractions(shifts, swaps, idempotency);
    }

    @Test
    void aForgedExpiredOrForeignTokenIsA401() throws Exception {
        mvc.perform(as(delete("/v1/shifts/1"), TestJwks.tokenSignedByAnotherKey(1, "Administrator")))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(delete("/v1/shifts/1"), TestJwks.expiredToken(1, "Administrator")))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(delete("/v1/shifts/1"), TestJwks.tokenFromAnotherIssuer(1, "Administrator")))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(delete("/v1/shifts/1"), "not-a-jwt")).andExpect(status().isUnauthorized());

        verifyNoInteractions(shifts);
    }

    @Test
    void anEmployeeMayNotWrite() throws Exception {
        String employee = TestJwks.employee(7);

        mvc.perform(as(json(post("/v1/shifts"), SHIFT), employee))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(as(json(put("/v1/shifts/1"), SHIFT), employee)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/v1/shifts/1"), employee)).andExpect(status().isForbidden());
        // The rule is by method, not by controller: the other write resources of the contract are covered too.
        mvc.perform(as(json(post("/v1/shiftassignments"), "{}"), employee)).andExpect(status().isForbidden());
        mvc.perform(as(json(post("/v1/shiftrequiredjobroles"), "{}"), employee)).andExpect(status().isForbidden());
        mvc.perform(as(json(post("/v1/shiftapprovals"), "{}"), employee)).andExpect(status().isForbidden());
        mvc.perform(as(json(post("/v1/shiftswapapprovals"), "{}"), employee)).andExpect(status().isForbidden());

        verifyNoInteractions(shifts, swaps, idempotency);
    }

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
    void anEmployeeMayNotRequestASwapForSomebodyElse() throws Exception {
        mvc.perform(as(swapRequest(8), TestJwks.employee(7)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        verifyNoInteractions(idempotency, swaps);
    }

    @Test
    void anEmployeeMayNotChangeOrWithdrawASwap() throws Exception {
        String employee = TestJwks.employee(7);

        mvc.perform(as(json(put("/v1/shiftswaps/1"), "{\"employeeToId\":9}"), employee))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete("/v1/shiftswaps/1"), employee)).andExpect(status().isForbidden());

        verifyNoInteractions(swaps);
    }

    @Test
    void aManagerAndAnAdministratorMayRequestASwapForAnyone() throws Exception {
        mvc.perform(as(swapRequest(8), TestJwks.manager())).andExpect(status().isOk());
        mvc.perform(as(swapRequest(8), TestJwks.administrator())).andExpect(status().isOk());

        verify(idempotency, times(2)).execute(anyString(), any(), any());
    }

    @Test
    void aRoleOutsideTheContractMayNotRequestASwapEvenForItself() throws Exception {
        mvc.perform(as(swapRequest(7), TestJwks.token(7, "Intern"))).andExpect(status().isForbidden());

        verifyNoInteractions(idempotency, swaps);
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
