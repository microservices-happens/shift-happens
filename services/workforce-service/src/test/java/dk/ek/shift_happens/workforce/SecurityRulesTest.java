package dk.ek.shift_happens.workforce;

import dk.ek.shift_happens.workforce.api.EmployeeController;
import dk.ek.shift_happens.workforce.application.EmployeeService;
import dk.ek.shift_happens.workforce.application.IdempotencyService;
import dk.ek.shift_happens.workforce.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security baseline of P1.7 without containers: the real filter chain and the real JWT decoder
 * (signature checked against the JWKS endpoint of {@link TestJwks}), with the use cases mocked.
 *
 * <p>Rules under test, from contracts/README.md and contracts/openapi/workforce.yaml: no or an invalid
 * token is a 401, GET needs any valid token, writes need Administrator or Manager (otherwise 403).
 */
@WebMvcTest(EmployeeController.class)
@Import(SecurityConfig.class)
class SecurityRulesTest {

    private static final String EMPLOYEE_BODY = """
            {"employeeNumber":"EMP-1","userRole":"Employee","firstName":"Ada","lastName":"Lovelace",
             "email":"ada@shift.dk","employmentStatus":"ACTIVE","hireDate":"2024-01-15"}
            """;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private EmployeeService employees;
    @MockitoBean
    private IdempotencyService idempotency;

    @Test
    void aRequestWithoutATokenIsA401ProblemAndReachesNoUseCase() throws Exception {
        mvc.perform(get("/v1/employees"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(json(post("/v1/employees"), EMPLOYEE_BODY)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/employees/1")).andExpect(status().isUnauthorized());

        verifyNoInteractions(employees, idempotency);
    }

    @Test
    void aForgedExpiredOrForeignTokenIsA401() throws Exception {
        mvc.perform(get("/v1/employees").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestJwks.tokenSignedByAnotherKey(1, "Administrator")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/employees").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestJwks.expiredToken(1, "Administrator")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/employees").header(HttpHeaders.AUTHORIZATION,
                        "Bearer " + TestJwks.tokenFromAnotherIssuer(1, "Administrator")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/employees").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(employees, idempotency);
    }

    @Test
    void anyValidTokenMayRead() throws Exception {
        for (String token : new String[] {TestJwks.employee(7), TestJwks.manager(), TestJwks.administrator()}) {
            mvc.perform(as(get("/v1/employees"), token)).andExpect(status().isOk());
        }
    }

    @Test
    void anEmployeeMayNotWrite() throws Exception {
        String employee = TestJwks.employee(7);

        mvc.perform(as(json(post("/v1/employees"), EMPLOYEE_BODY), employee))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(as(json(patch("/v1/employees/1"), EMPLOYEE_BODY), employee)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/v1/employees/1"), employee)).andExpect(status().isForbidden());
        // The rule is by method, not by controller: the other write resources of the contract are covered too.
        mvc.perform(as(json(post("/v1/departments"), "{}"), employee)).andExpect(status().isForbidden());
        mvc.perform(as(json(put("/v1/jobroles/1"), "{}"), employee)).andExpect(status().isForbidden());
        mvc.perform(as(delete("/v1/employeecontracts/1"), employee)).andExpect(status().isForbidden());

        verifyNoInteractions(employees, idempotency);
    }

    @Test
    void aRoleOutsideTheContractMayNotWrite() throws Exception {
        mvc.perform(as(delete("/v1/employees/1"), TestJwks.token(7, "Intern"))).andExpect(status().isForbidden());

        verifyNoInteractions(employees);
    }

    @Test
    void aManagerAndAnAdministratorMayWrite() throws Exception {
        mvc.perform(as(delete("/v1/employees/1").header("X-Correlation-Id", "corr-1"), TestJwks.manager())).andExpect(status().isNoContent());
        mvc.perform(as(delete("/v1/employees/2").header("X-Correlation-Id", "corr-2"), TestJwks.administrator())).andExpect(status().isNoContent());

        verify(employees).delete(1, "corr-1");
        verify(employees).delete(2, "corr-2");
    }

    @Test
    void theJwksIsFetchedOnceAndThenCached() throws Exception {
        mvc.perform(as(get("/v1/employees"), TestJwks.manager())).andExpect(status().isOk());
        int afterTheFirstToken = TestJwks.fetches();

        for (int i = 0; i < 5; i++) {
            mvc.perform(as(get("/v1/employees"), TestJwks.employee(i))).andExpect(status().isOk());
        }

        assertThat(afterTheFirstToken).isPositive();
        assertThat(TestJwks.fetches()).isEqualTo(afterTheFirstToken);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
