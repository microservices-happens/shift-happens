package dk.ek.shift_happens.ai;

import dk.ek.shift_happens.ai.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security baseline of P1.7 without containers: the real filter chain and the real JWT decoder
 * (signature checked against the JWKS endpoint of {@link TestJwks}).
 *
 * <p>ai-service has no browser API, so there is no controller to reach: a request that passes security
 * ends in 404, and a request that does not is a 401 or 403. /health itself is an actuator endpoint and
 * is asserted against the running service in {@code AiServiceApplicationIT}.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class SecurityRulesTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void aRequestWithoutATokenIsA401Problem() throws Exception {
        mvc.perform(get("/v1/suggestions"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(post("/v1/suggestions")).andExpect(status().isUnauthorized());
        mvc.perform(get("/info")).andExpect(status().isUnauthorized());
    }

    @Test
    void aForgedExpiredOrForeignTokenIsA401() throws Exception {
        mvc.perform(as(get("/v1/suggestions"), TestJwks.tokenSignedByAnotherKey(1, "Administrator")))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(get("/v1/suggestions"), TestJwks.expiredToken(1, "Administrator")))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(get("/v1/suggestions"), TestJwks.tokenFromAnotherIssuer(1, "Administrator")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsNotBehindTheToken() throws Exception {
        // Not 401: the chain lets it through. The actuator endpoint itself is not part of this slice.
        mvc.perform(get("/health")).andExpect(status().isNotFound());
    }

    @Test
    void anyValidTokenPassesSecurityOnARead() throws Exception {
        mvc.perform(as(get("/v1/suggestions"), TestJwks.employee(7))).andExpect(status().isNotFound());
    }

    @Test
    void aWriteNeedsAdministratorOrManager() throws Exception {
        mvc.perform(as(post("/v1/suggestions"), TestJwks.employee(7)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
        mvc.perform(as(post("/v1/suggestions"), TestJwks.manager())).andExpect(status().isNotFound());
        mvc.perform(as(post("/v1/suggestions"), TestJwks.administrator())).andExpect(status().isNotFound());
    }

    @Test
    void theJwksIsFetchedOnceAndThenCached() throws Exception {
        mvc.perform(as(get("/v1/suggestions"), TestJwks.manager())).andExpect(status().isNotFound());
        int afterTheFirstToken = TestJwks.fetches();

        for (int i = 0; i < 5; i++) {
            mvc.perform(as(get("/v1/suggestions"), TestJwks.employee(i))).andExpect(status().isNotFound());
        }

        assertThat(afterTheFirstToken).isPositive();
        assertThat(TestJwks.fetches()).isEqualTo(afterTheFirstToken);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
