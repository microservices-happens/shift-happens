package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.scheduling.ContractSecurityMatrix.Caller;
import dk.ek.shift_happens.scheduling.application.SchedulingQueryService;
import dk.ek.shift_happens.scheduling.config.JwtConfig;
import dk.ek.shift_happens.scheduling.config.QuerySecurityConfig;
import graphql.language.FieldDefinition;
import graphql.language.InputValueDefinition;
import graphql.language.ListType;
import graphql.language.NonNullType;
import graphql.language.ObjectTypeDefinition;
import graphql.language.Type;
import graphql.language.TypeName;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.graphql.GraphQlAutoConfiguration;
import org.springframework.boot.autoconfigure.graphql.servlet.GraphQlWebMvcAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The security matrix of P6.6 for the query role, without containers: every root operation of the
 * GraphQL schema (src/main/resources/graphql/scheduling.graphqls, kept equal to contracts/graphql by
 * {@code GraphQlSchemaMatchesContractTest}) × {no token, expired, forged signature, wrong issuer,
 * malformed, each role, an unknown role}, through the real filter chain, the real JWT decoder and the real
 * GraphQL endpoint, with the read model mocked.
 *
 * <p>The rule (contracts/README.md, the SDL header): GraphQL is the read API, so it needs any valid token;
 * an invalid or missing token is a 401 before GraphQL runs. Which rows an Employee sees is decided in the
 * query use cases and asserted in {@code SchedulingGraphQlIT}, not here.
 */
@WebMvcTest
@ActiveProfiles("query")
@ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class})
@Import({QuerySecurityConfig.class, JwtConfig.class, QuerySecurityMatrixTest.GraphQlLayer.class})
class QuerySecurityMatrixTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.security.jwks-url", TestJwks::jwksUrl);
        registry.add("app.security.issuer", () -> TestJwks.ISSUER);
    }

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private SchedulingQueryService queries;

    /** One query per root field of the schema, with every required argument filled in. */
    static Stream<Arguments> matrix() throws IOException {
        String sdl = new ClassPathResource("graphql/scheduling.graphqls").getContentAsString(StandardCharsets.UTF_8);
        TypeDefinitionRegistry schema = new SchemaParser().parse(sdl);
        List<Arguments> cells = new ArrayList<>();
        for (String root : List.of("Query", "Mutation", "Subscription")) {
            schema.getType(root, ObjectTypeDefinition.class).ifPresent(type -> {
                for (FieldDefinition field : type.getFieldDefinitions()) {
                    String operation = root.toLowerCase() + " { " + call(field, schema) + " }";
                    for (Caller caller : Caller.values()) {
                        if (caller != Caller.EMPLOYEE_FOR_SOMEONE_ELSE) {
                            cells.add(Arguments.of(root + "." + field.getName(), operation, caller));
                        }
                    }
                }
            });
        }
        return cells.stream();
    }

    @ParameterizedTest(name = "{0} as {2}")
    @MethodSource("matrix")
    void everyGraphQlOperationNeedsAValidTokenAndAcceptsEveryRole(String name, String operation, Caller caller)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("query", operation)));
        if (caller.authorization() != null) {
            request.header(HttpHeaders.AUTHORIZATION, caller.authorization());
        }

        if (!caller.validToken()) {
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer")));
            verifyNoInteractions(queries);
            return;
        }
        MvcResult result = mvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus()).as(name).isEqualTo(200);
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("errors").isMissingNode() || body.path("errors").isEmpty())
                .as(name + " answered without errors: " + body).isTrue();
        assertThat(body.path("data").has(name.substring(name.indexOf('.') + 1))).as(name + " data").isTrue();
    }

    private static String call(FieldDefinition field, TypeDefinitionRegistry schema) {
        List<String> arguments = field.getInputValueDefinitions().stream()
                .filter(argument -> argument.getType() instanceof NonNullType)
                .map(argument -> argument.getName() + ": " + example(argument))
                .collect(Collectors.toList());
        String call = field.getName() + (arguments.isEmpty() ? "" : "(" + String.join(", ", arguments) + ")");
        boolean leaf = Set.of("Int", "Float", "String", "Boolean", "ID", "DateTime", "Date")
                .contains(typeName(field.getType())) || schema.getType(typeName(field.getType()))
                .map(definition -> !(definition instanceof ObjectTypeDefinition)).orElse(true);
        return leaf ? call : call + " { __typename }";
    }

    private static String example(InputValueDefinition argument) {
        return switch (typeName(argument.getType())) {
            case "Int", "Float" -> "1";
            case "Boolean" -> "true";
            case "DateTime" -> "\"2026-11-02T00:00:00Z\"";
            case "Date" -> "\"2026-11-02\"";
            case "String", "ID" -> "\"x\"";
            default -> throw new IllegalStateException("no example for the required argument "
                    + argument.getName() + " of type " + typeName(argument.getType()));
        };
    }

    @SuppressWarnings("rawtypes")
    private static String typeName(Type type) {
        if (type instanceof NonNullType nonNull) {
            return typeName(nonNull.getType());
        }
        if (type instanceof ListType list) {
            return typeName(list.getType());
        }
        return ((TypeName) type).getName();
    }

    /** The GraphQL controller, scalars and error resolver, which a web slice does not scan by itself. */
    @TestConfiguration
    @ComponentScan("dk.ek.shift_happens.scheduling.api.graphql")
    static class GraphQlLayer {
    }
}
