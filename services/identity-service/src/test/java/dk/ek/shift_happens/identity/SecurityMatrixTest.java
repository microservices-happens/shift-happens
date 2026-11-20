package dk.ek.shift_happens.identity;

import dk.ek.shift_happens.identity.ContractSecurityMatrix.Caller;
import dk.ek.shift_happens.identity.ContractSecurityMatrix.Operation;
import dk.ek.shift_happens.identity.ContractSecurityMatrix.Outcome;
import dk.ek.shift_happens.identity.application.LoginService;
import dk.ek.shift_happens.identity.application.OidcLoginService;
import dk.ek.shift_happens.identity.application.SetPasswordService;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security matrix of P6.6 without containers: every operation of contracts/openapi/identity.yaml ×
 * {no token, expired, forged signature, wrong issuer, malformed, each role, an unknown role}, through the
 * real filter chain and the real JWT decoder, with every controller loaded. SetPasswordService
 * is real (only its repository is mocked), because the "Administrator, or the employee themself" rule of
 * {@code PUT /v1/auth/accounts/{employeeId}/password} lives there.
 *
 * <p>The expected status comes from the contract's own role rules ({@link ContractSecurityMatrix}), so a
 * new endpoint is in the matrix as soon as it is in the contract. Login, OIDC (an unknown
 * email is refused: {@code OidcIT}, {@code OidcLoginServiceTest}) and the real database stay in the ITs.
 *
 * <p>identity's 401 is Spring's bare Bearer challenge (WWW-Authenticate, no body), unlike the other
 * services, so the 401 cells assert the challenge only; its 403 is problem+json from ApiExceptionHandler.
 */
@WebMvcTest
@Import({SetPasswordService.class, SecurityMatrixTest.IdentitySecurity.class})
class SecurityMatrixTest {

    private static final List<Operation> CONTRACT = ContractSecurityMatrix.operations("static/openapi/identity.yaml");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // identity verifies with its own signing key: the one TestJwks signs with.
        registry.add("app.jwt.private-key-path", TestJwks::privateKeyPath);
        registry.add("app.jwt.issuer", () -> TestJwks.ISSUER);
        registry.add("app.password.pepper", () -> "test-pepper");
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private RequestMappingHandlerMapping handlers;
    @MockitoBean
    private LoginService login;
    @MockitoBean
    private OidcLoginService oidcLogin;
    /** The real SetPasswordService runs, so its "Administrator or the employee themself" rule is in the matrix. */
    @MockitoBean
    private AccountRepository accounts;

    static Stream<Arguments> matrix() {
        return ContractSecurityMatrix.cells(CONTRACT);
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("matrix")
    void everyContractOperationAnswersEveryCallerAsTheRoleRulesSay(Operation operation, Caller caller,
                                                                   Outcome expected) throws Exception {
        MockHttpServletRequestBuilder request = requestFor(operation, caller);

        switch (expected) {
            case UNAUTHORIZED -> {
                mvc.perform(request)
                        .andExpect(status().isUnauthorized())
                        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
                verifyNoInteractions(login, oidcLogin, accounts);
            }
            case FORBIDDEN -> {
                mvc.perform(request)
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.status").value(403));
                verifyNoInteractions(login, oidcLogin, accounts);
            }
            case ALLOWED -> assertPassesSecurity(operation, request);
        }
    }

    /**
     * Both directions, so neither side can drift silently: every contract operation has a handler (an
     * allowed caller would otherwise only see a 404), and every handler is in the contract (a new endpoint
     * would otherwise have no rule in the matrix above).
     */
    @Test
    void theMatrixCoversExactlyTheEndpointsTheServiceServes() {
        Set<String> contract = CONTRACT.stream()
                .filter(operation -> !operation.path().equals("/health"))
                .map(operation -> operation.method() + " " + normalised(operation.path()))
                .collect(Collectors.toCollection(TreeSet::new));
        // Spring's own /error controller is not an endpoint of this service. A mapping without a method
        // condition answers every method, so it shows up as "ANY" and cannot match the contract.
        Set<String> served = handlers.getHandlerMethods().entrySet().stream()
                .filter(entry -> !entry.getValue().getBeanType().getPackageName().startsWith("org.springframework."))
                .map(Map.Entry::getKey)
                .flatMap(mapping -> (mapping.getMethodsCondition().getMethods().isEmpty()
                        ? Stream.of("ANY") : mapping.getMethodsCondition().getMethods().stream().map(Enum::name))
                        .flatMap(method -> mapping.getPatternValues().stream()
                                .map(path -> method + " " + normalised(path))))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(served).as("endpoints served by identity-service").isEqualTo(contract);
    }

    private void assertPassesSecurity(Operation operation, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result;
        try {
            result = mvc.perform(request).andReturn();
        } catch (Exception thrownByTheHandler) {
            // Security answers with a 401/403 response, never with an exception: the mocked use case
            // returned nothing usable, which proves the request got past the filter chain.
            return;
        }
        assertThat(result.getResponse().getStatus()).as(operation + " status").isNotIn(401, 403);
        if (!operation.path().equals("/health")) {
            assertThat(result.getHandler()).as(operation + " handler").isInstanceOf(HandlerMethod.class);
        }
    }

    private static MockHttpServletRequestBuilder requestFor(Operation operation, Caller caller) {
        MockHttpServletRequestBuilder request = request(operation.method(), operation.uri(caller));
        String authorization = caller.authorization();
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (operation.method() == HttpMethod.POST) {
            request.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        String body = operation.body(caller);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return request;
    }

    private static String normalised(String path) {
        return path.replaceAll("\\{[^}]+}", "{}");
    }

    /** identity's SecurityConfig is package-private, so it is picked up by name. */
    @TestConfiguration
    @ComponentScan(basePackages = "dk.ek.shift_happens.identity.config", useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*\\.config\\.SecurityConfig"))
    static class IdentitySecurity {
    }
}
