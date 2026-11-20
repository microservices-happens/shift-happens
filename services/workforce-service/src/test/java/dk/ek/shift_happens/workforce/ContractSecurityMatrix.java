package dk.ek.shift_happens.workforce;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The security matrix of P6.6, derived from the OpenAPI contract instead of a hand-copied endpoint list:
 * every operation in {@code contracts/openapi/<service>.yaml} × every kind of caller, with the status the
 * role rules of contracts/README.md require.
 *
 * <p>The rule of an operation is read from the contract itself:
 * <ul>
 *   <li>{@code security: []} is public;</li>
 *   <li>a description starting with {@code Roles: ...} lists the roles that may call it, and
 *       "an Employee for themself ({@code field} = JWT employeeId)" or "the employee themself" (with an
 *       {@code {employeeId}} path parameter) adds an Employee acting for themselves;</li>
 *   <li>any other {@code GET} needs any valid token ("GET requests need any valid token").</li>
 * </ul>
 * A write without a {@code Roles:} line has no rule, and building the matrix fails on it, so a new
 * endpoint cannot be added to the contract without saying who may call it.
 *
 * <p>Copied per service on purpose: there is no shared test library.
 */
final class ContractSecurityMatrix {

    /** The employee id of the Employee caller; "someone else" is this id + 1. */
    static final long EMPLOYEE_ID = 7;

    private static final Set<String> METHODS = Set.of("get", "post", "put", "patch", "delete");
    private static final Pattern ROLES = Pattern.compile("Roles:\\s*([^.]*)\\.");
    private static final Pattern SELF_FIELD = Pattern.compile("\\((\\w+)\\s*=\\s*JWT employeeId\\)");
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{(\\w+)}");
    private static final ObjectMapper JSON = new ObjectMapper();

    private ContractSecurityMatrix() {
    }

    enum Access { PUBLIC, ANY_TOKEN, ROLES }

    enum Outcome { ALLOWED, UNAUTHORIZED, FORBIDDEN }

    /** Who calls. The token kinds come from {@link TestJwks}. */
    enum Caller {
        NO_TOKEN(null),
        EXPIRED_TOKEN(null),
        FORGED_SIGNATURE(null),
        WRONG_ISSUER(null),
        MALFORMED_TOKEN(null),
        ADMINISTRATOR("Administrator"),
        MANAGER("Manager"),
        EMPLOYEE("Employee"),
        /** An Employee acting for another employee; only generated where the contract has a self rule. */
        EMPLOYEE_FOR_SOMEONE_ELSE("Employee"),
        /** A correctly signed token whose role is not one of the contract's three. */
        UNKNOWN_ROLE("Intern");

        private final String role;

        Caller(String role) {
            this.role = role;
        }

        boolean validToken() {
            return role != null;
        }

        /** The value of {@code Authorization}, or null for no header. */
        String authorization() {
            String token = switch (this) {
                case NO_TOKEN -> null;
                case EXPIRED_TOKEN -> TestJwks.expiredToken(EMPLOYEE_ID, "Administrator");
                case FORGED_SIGNATURE -> TestJwks.tokenSignedByAnotherKey(EMPLOYEE_ID, "Administrator");
                case WRONG_ISSUER -> TestJwks.tokenFromAnotherIssuer(EMPLOYEE_ID, "Administrator");
                case MALFORMED_TOKEN -> "not-a-jwt";
                case ADMINISTRATOR -> TestJwks.administrator();
                case MANAGER -> TestJwks.manager();
                case EMPLOYEE, EMPLOYEE_FOR_SOMEONE_ELSE -> TestJwks.employee(EMPLOYEE_ID);
                case UNKNOWN_ROLE -> TestJwks.token(EMPLOYEE_ID, role);
            };
            return token == null ? null : "Bearer " + token;
        }

        /** The employeeId claim of the token: {@link TestJwks} gives Administrator and Manager 900. */
        long employeeId() {
            return this == ADMINISTRATOR || this == MANAGER ? 900 : EMPLOYEE_ID;
        }

        /** The employee id the request acts for, in the body or the path, when the rule depends on it. */
        long actsFor() {
            return this == EMPLOYEE_FOR_SOMEONE_ELSE ? EMPLOYEE_ID + 1 : EMPLOYEE_ID;
        }
    }

    /**
     * Who may call an operation. {@code selfField} is the body field (or {@code {employeeId}} path
     * parameter, when {@code selfInPath}) that must equal the caller's JWT employeeId. "An Employee for
     * themself" is the Employee role only ({@code selfForAnyRole} false); "the employee themself" is any
     * account acting for itself.
     */
    record Rule(Access access, Set<String> roles, String selfField, boolean selfInPath, boolean selfForAnyRole) {

        boolean hasSelfRule() {
            return selfField != null;
        }
    }

    record Operation(HttpMethod method, String path, Rule rule, Map<String, Object> bodySchema,
                     Map<String, Object> schemas) {

        Outcome expected(Caller caller) {
            if (rule.access() == Access.PUBLIC) {
                return Outcome.ALLOWED;
            }
            if (!caller.validToken()) {
                return Outcome.UNAUTHORIZED;
            }
            if (rule.access() == Access.ANY_TOKEN || rule.roles().contains(caller.role)) {
                return Outcome.ALLOWED;
            }
            boolean forThemself = rule.hasSelfRule() && caller.actsFor() == caller.employeeId()
                    && (rule.selfForAnyRole() || caller == Caller.EMPLOYEE);
            return forThemself ? Outcome.ALLOWED : Outcome.FORBIDDEN;
        }

        /** The concrete URI: every path parameter is 1, except the self parameter of a self rule. */
        String uri(Caller caller) {
            Matcher matcher = PATH_PARAMETER.matcher(path);
            StringBuilder uri = new StringBuilder();
            while (matcher.find()) {
                boolean self = rule.selfInPath() && matcher.group(1).equals(rule.selfField());
                matcher.appendReplacement(uri, self ? Long.toString(caller.actsFor()) : "1");
            }
            return matcher.appendTail(uri).toString();
        }

        /**
         * A body that satisfies the contract schema, so a request that passes security reaches the
         * handler (and a self rule in the body is evaluated) instead of stopping at validation.
         */
        String body(Caller caller) {
            if (bodySchema == null) {
                return null;
            }
            String self = rule.selfInPath() ? null : rule.selfField();
            try {
                return JSON.writeValueAsString(example(bodySchema, self, caller.actsFor()));
            } catch (JsonProcessingException cannotWrite) {
                throw new IllegalStateException(cannotWrite);
            }
        }

        @SuppressWarnings("unchecked")
        private Object example(Map<String, Object> schema, String selfField, long selfId) {
            Map<String, Object> resolved = resolve(schema);
            Object type = resolved.get("type");
            String kind = type instanceof List<?> types ? (String) types.get(0) : (String) type;
            if (resolved.containsKey("enum")) {
                return ((List<Object>) resolved.get("enum")).get(0);
            }
            if (kind == null || kind.equals("object")) {
                Map<String, Object> object = new LinkedHashMap<>();
                Map<String, Object> properties = (Map<String, Object>) resolved.getOrDefault("properties", Map.of());
                properties.forEach((name, property) -> {
                    Map<String, Object> propertySchema = resolve((Map<String, Object>) property);
                    if (Boolean.TRUE.equals(propertySchema.get("readOnly"))) {
                        return;
                    }
                    object.put(name, name.equals(selfField) ? selfId : example(propertySchema, null, selfId));
                });
                return object;
            }
            return switch (kind) {
                case "integer", "number" -> 1;
                case "boolean" -> true;
                case "array" -> List.of();
                default -> switch (String.valueOf(resolved.get("format"))) {
                    case "date" -> "2026-01-05";
                    case "date-time" -> "2026-01-05T08:00:00Z";
                    case "uuid" -> "00000000-0000-0000-0000-000000000001";
                    case "email" -> "someone@shift.dk";
                    default -> "x".repeat(Math.max(1, ((Number) resolved.getOrDefault("minLength", 1)).intValue()));
                };
            };
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> resolve(Map<String, Object> schema) {
            Object ref = schema.get("$ref");
            if (ref == null) {
                return schema;
            }
            String name = ((String) ref).substring("#/components/schemas/".length());
            return resolve((Map<String, Object>) schemas.get(name));
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    /** Every operation of the contract on the classpath (the build copies contracts/openapi there). */
    @SuppressWarnings("unchecked")
    static List<Operation> operations(String contract) {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource(contract).getInputStream()) {
            root = new Yaml().load(in);
        } catch (IOException cannotRead) {
            throw new UncheckedIOException(cannotRead);
        }
        Map<String, Object> schemas = (Map<String, Object>) ((Map<String, Object>) root.getOrDefault(
                "components", Map.of())).getOrDefault("schemas", Map.of());
        List<Operation> operations = new ArrayList<>();
        ((Map<String, Map<String, Object>>) root.get("paths")).forEach((path, item) -> item.forEach((method, value) -> {
            if (!METHODS.contains(method)) {
                return;
            }
            Map<String, Object> operation = (Map<String, Object>) value;
            operations.add(new Operation(HttpMethod.valueOf(method.toUpperCase()), path,
                    rule(method, path, operation), jsonBody(operation), schemas));
        }));
        return operations;
    }

    /** operation × caller × expected outcome; the Employee-for-someone-else row only where it matters. */
    static Stream<Arguments> cells(List<Operation> operations) {
        return operations.stream().flatMap(operation -> callersOf(operation).stream()
                .map(caller -> Arguments.of(operation, caller, operation.expected(caller))));
    }

    private static List<Caller> callersOf(Operation operation) {
        if (operation.rule().access() == Access.PUBLIC) {
            // An invalid token on a public path is not part of the rules; "without authentication" is.
            return List.of(Caller.NO_TOKEN);
        }
        List<Caller> callers = new ArrayList<>(List.of(Caller.values()));
        if (!operation.rule().hasSelfRule()) {
            callers.remove(Caller.EMPLOYEE_FOR_SOMEONE_ELSE);
        }
        return callers;
    }

    @SuppressWarnings("unchecked")
    private static Rule rule(String method, String path, Map<String, Object> operation) {
        Object security = operation.get("security");
        if (security instanceof List<?> requirements && requirements.isEmpty()) {
            return new Rule(Access.PUBLIC, Set.of(), null, false, false);
        }
        String description = String.valueOf(operation.getOrDefault("description", ""));
        Matcher roles = ROLES.matcher(description);
        if (!roles.find()) {
            if (method.equals("get")) {
                return new Rule(Access.ANY_TOKEN, Set.of(), null, false, false);
            }
            throw new IllegalStateException(method.toUpperCase() + " " + path
                    + " has no 'Roles:' rule in the contract, so the matrix cannot say who may call it");
        }
        String allowed = roles.group(1);
        Set<String> named = new LinkedHashSet<>();
        for (String role : List.of("Administrator", "Manager")) {
            if (allowed.contains(role)) {
                named.add(role);
            }
        }
        boolean self = allowed.matches("(?is).*employee\\b.*\\b(themself|themselves|their own)\\b.*");
        if (!self && allowed.contains("Employee")) {
            named.add("Employee");
        }
        if (!self) {
            return new Rule(Access.ROLES, Set.copyOf(named), null, false, false);
        }
        boolean anyRole = !allowed.contains("Employee");
        Matcher field = SELF_FIELD.matcher(allowed);
        if (field.find()) {
            return new Rule(Access.ROLES, Set.copyOf(named), field.group(1), false, anyRole);
        }
        if (path.contains("{employeeId}")) {
            return new Rule(Access.ROLES, Set.copyOf(named), "employeeId", true, anyRole);
        }
        throw new IllegalStateException(method.toUpperCase() + " " + path
                + " allows an Employee for themself but names no field or {employeeId} to compare");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonBody(Map<String, Object> operation) {
        Map<String, Object> body = (Map<String, Object>) operation.get("requestBody");
        if (body == null) {
            return null;
        }
        Map<String, Object> content = (Map<String, Object>) body.getOrDefault("content", Map.of());
        Map<String, Object> json = (Map<String, Object>) content.get("application/json");
        return json == null ? null : (Map<String, Object>) json.get("schema");
    }
}
