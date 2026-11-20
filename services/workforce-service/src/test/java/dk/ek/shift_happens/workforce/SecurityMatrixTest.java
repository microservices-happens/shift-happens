package dk.ek.shift_happens.workforce;

import dk.ek.shift_happens.workforce.ContractSecurityMatrix.Caller;
import dk.ek.shift_happens.workforce.ContractSecurityMatrix.Operation;
import dk.ek.shift_happens.workforce.ContractSecurityMatrix.Outcome;
import dk.ek.shift_happens.workforce.application.EmployeeService;
import dk.ek.shift_happens.workforce.application.EmployeeTermsService;
import dk.ek.shift_happens.workforce.application.IdempotencyService;
import dk.ek.shift_happens.workforce.config.SecurityConfig;
import dk.ek.shift_happens.workforce.infrastructure.persistence.EmployeeTermsRepository;
import dk.ek.shift_happens.workforce.infrastructure.persistence.ReferenceDataRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.List;
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
 * The security matrix of P6.6 without containers: every operation of contracts/openapi/workforce.yaml ×
 * {no token, expired, forged signature, wrong issuer, malformed, each role, an unknown role}, through the
 * real filter chain and the real JWT decoder, with every controller loaded and the use cases mocked.
 *
 * <p>The expected status comes from the contract's own role rules ({@link ContractSecurityMatrix}), so a
 * new endpoint is in the matrix as soon as it is in the contract. {@link SecurityRulesTest} keeps the
 * hand-picked cases (JWKS caching, which use case is called).
 */
@WebMvcTest
@Import(SecurityConfig.class)
class SecurityMatrixTest {

    private static final List<Operation> CONTRACT = ContractSecurityMatrix.operations("static/openapi/workforce.yaml");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private RequestMappingHandlerMapping handlers;
    @MockitoBean
    private EmployeeService employees;
    @MockitoBean
    private EmployeeTermsService employeeTerms;
    @MockitoBean
    private EmployeeTermsRepository employeeTermsRepository;
    @MockitoBean
    private ReferenceDataRepository referenceData;
    @MockitoBean
    private IdempotencyService idempotency;

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
                        .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")))
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.status").value(401));
                verifyNoInteractions(employees, employeeTerms, employeeTermsRepository, referenceData, idempotency);
            }
            case FORBIDDEN -> {
                mvc.perform(request)
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                        .andExpect(jsonPath("$.status").value(403));
                // An Idempotency-Key was sent: a refused caller never reaches a stored response either.
                verifyNoInteractions(employees, employeeTerms, employeeTermsRepository, referenceData, idempotency);
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
        Set<String> served = handlers.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getMethodsCondition().getMethods().stream()
                        .flatMap(method -> mapping.getPatternValues().stream()
                                .map(path -> method + " " + normalised(path))))
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(served).as("endpoints served by workforce-service").isEqualTo(contract);
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
}
