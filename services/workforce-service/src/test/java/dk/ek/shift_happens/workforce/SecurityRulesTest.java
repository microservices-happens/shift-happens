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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security baseline of P1.7 without containers: the real filter chain and the real JWT decoder
 * (signature checked against the JWKS endpoint of {@link TestJwks}), with the use cases mocked.
 *
 * <p>The 401/403 rules for every endpoint and caller are in {@link SecurityMatrixTest}, derived from
 * contracts/openapi/workforce.yaml (P6.6). This class keeps what the matrix does not assert: which use
 * case an allowed write reaches, and that the JWKS is fetched once and then cached.
 */
@WebMvcTest(EmployeeController.class)
@Import(SecurityConfig.class)
class SecurityRulesTest {

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
}
