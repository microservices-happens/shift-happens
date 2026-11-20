package dk.ek.shift_happens.ai;

import dk.ek.shift_happens.ai.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security baseline of P1.7 and the matrix of P6.6 without containers: the real filter chain and the
 * real JWT decoder (signature checked against the JWKS endpoint of {@link TestJwks}).
 *
 * <p>ai-service has no browser API and no OpenAPI contract (contracts/README.md: "– (health only)"), so
 * {@link #theServiceServesNoEndpointOutsideTheContract()} pins that, and the matrix runs every HTTP method
 * against a non-health path: a request that passes security ends in 404, and a request that does not is a
 * 401 or 403. /health itself is an actuator endpoint and is asserted against the running service in
 * {@code AiServiceApplicationIT}.
 */
@WebMvcTest
@Import(SecurityConfig.class)
class SecurityRulesTest {

    private static final List<HttpMethod> METHODS =
            List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private RequestMappingHandlerMapping handlers;

    /** method × caller → expected status, from the role rules of contracts/README.md. */
    static Stream<Arguments> matrix() {
        List<Arguments> cells = new ArrayList<>();
        for (HttpMethod method : METHODS) {
            boolean read = method == HttpMethod.GET;
            cells.add(Arguments.of(method, "no token", null, 401));
            cells.add(Arguments.of(method, "expired", TestJwks.expiredToken(1, "Administrator"), 401));
            cells.add(Arguments.of(method, "forged signature", TestJwks.tokenSignedByAnotherKey(1, "Administrator"), 401));
            cells.add(Arguments.of(method, "wrong issuer", TestJwks.tokenFromAnotherIssuer(1, "Administrator"), 401));
            cells.add(Arguments.of(method, "malformed", "not-a-jwt", 401));
            cells.add(Arguments.of(method, "Administrator", TestJwks.administrator(), 404));
            cells.add(Arguments.of(method, "Manager", TestJwks.manager(), 404));
            cells.add(Arguments.of(method, "Employee", TestJwks.employee(7), read ? 404 : 403));
            cells.add(Arguments.of(method, "unknown role", TestJwks.token(7, "Intern"), read ? 404 : 403));
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0} /v1/suggestions as {1} -> {3}")
    @MethodSource("matrix")
    void everyMethodAnswersEveryCallerAsTheRoleRulesSay(HttpMethod method, String caller, String token,
                                                        int expected) throws Exception {
        MockHttpServletRequestBuilder request = request(method, "/v1/suggestions");
        if (token != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }

        var result = mvc.perform(request).andExpect(status().is(expected));
        if (expected == 401) {
            result.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
        }
        if (expected != 404) {
            result.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(expected));
        }
    }

    /** A new endpoint here needs a contract (and a row in this matrix) first. */
    @Test
    void theServiceServesNoEndpointOutsideTheContract() {
        // Spring's own /error controller is not an endpoint of this service.
        assertThat(handlers.getHandlerMethods().values()).allSatisfy(handler ->
                assertThat(handler.getBeanType().getPackageName()).startsWith("org.springframework."));
    }

    @Test
    void anActuatorPathOtherThanHealthIsBehindTheToken() throws Exception {
        mvc.perform(get("/info")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthIsNotBehindTheToken() throws Exception {
        // Not 401: the chain lets it through. The actuator endpoint itself is not part of this slice.
        mvc.perform(get("/health")).andExpect(status().isNotFound());
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
