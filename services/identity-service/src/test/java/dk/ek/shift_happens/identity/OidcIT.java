package dk.ek.shift_happens.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Google/GitHub login (P2.3) end to end inside the service, against stub providers: the account comes
 * from a workforce event, the browser is redirected with a state and an S256 challenge, the callback
 * exchanges the code, and the frontend receives a JWT that verifies with only the public JWKS.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OidcIT {

    private static final String ISSUER = "shift-happens-identity";
    private static final String REDIRECT_BASE = "https://gateway.test/api/v1/auth/oidc";
    private static final String FRONTEND_CALLBACK = "https://frontend.test/login/callback";
    private static final StubProviders STUB = new StubProviders();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "shift");
        registry.add("spring.rabbitmq.password", () -> "shift_dev");
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
        registry.add("app.password.pepper", () -> "it-pepper");
        registry.add("app.jwt.issuer", () -> ISSUER);

        registry.add("app.oidc.redirect-base-url", () -> REDIRECT_BASE);
        registry.add("app.oidc.frontend-callback-url", () -> FRONTEND_CALLBACK);
        registry.add("app.oidc.google.client-id", () -> StubProviders.GOOGLE_CLIENT_ID);
        registry.add("app.oidc.google.client-secret", () -> "google-secret");
        registry.add("app.oidc.google.authorization-uri", () -> STUB.baseUrl() + "/google/authorize");
        registry.add("app.oidc.google.token-uri", () -> STUB.baseUrl() + "/google/token");
        registry.add("app.oidc.google.jwk-set-uri", () -> STUB.baseUrl() + "/google/jwks");
        registry.add("app.oidc.github.client-id", () -> StubProviders.GITHUB_CLIENT_ID);
        registry.add("app.oidc.github.client-secret", () -> "github-secret");
        registry.add("app.oidc.github.authorization-uri", () -> STUB.baseUrl() + "/github/authorize");
        registry.add("app.oidc.github.token-uri", () -> STUB.baseUrl() + "/github/token");
        registry.add("app.oidc.github.user-uri", () -> STUB.baseUrl() + "/github/user");
        registry.add("app.oidc.github.emails-uri", () -> STUB.baseUrl() + "/github/emails");
    }

    // java.net.http does not follow redirects by default: every 302 is asserted, not followed.
    private final HttpClient browser = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private ObjectMapper objectMapper;
    @Value("${app.events.exchange}")
    private String exchange;

    @Test
    void googleLoginRedirectsWithAJwtForTheMatchingActiveEmployee() throws Exception {
        employee(601, 1, "ada@shift.dk", "Manager", "ACTIVE");
        googleSays("google-601", "Ada@Shift.dk", true);

        HttpResponse<String> authorize = get("/v1/auth/oidc/google/authorize");
        assertThat(authorize.statusCode()).isEqualTo(302);
        URI providerUrl = location(authorize);
        Map<String, String> query = query(providerUrl);
        assertThat(providerUrl.toString()).startsWith(STUB.baseUrl() + "/google/authorize?");
        assertThat(query).containsEntry("response_type", "code")
                .containsEntry("client_id", StubProviders.GOOGLE_CLIENT_ID)
                .containsEntry("redirect_uri", REDIRECT_BASE + "/google/callback")
                .containsEntry("scope", "openid email")
                .containsEntry("code_challenge_method", "S256");
        assertThat(query.get("state")).isNotBlank();

        String fragment = callback("google", query.get("state"));

        // PKCE: the verifier sent to the token endpoint hashes to the challenge the browser carried.
        assertThat(sha256Base64Url(STUB.lastTokenRequest.get("code_verifier"))).isEqualTo(query.get("code_challenge"));
        assertThat(STUB.lastTokenRequest).containsEntry("code", "the-code")
                .containsEntry("redirect_uri", REDIRECT_BASE + "/google/callback");
        Jwt jwt = verifyWithPublicJwks(token(fragment));
        assertThat(jwt.getSubject()).isEqualTo("601");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("Manager");
    }

    @Test
    void gitHubLoginUsesThePrimaryVerifiedEmail() throws Exception {
        employee(602, 1, "linus@shift.dk", "Employee", "ACTIVE");
        gitHubSays(9602, """
                [{"email":"linus@users.noreply.github.com","primary":false,"verified":true},
                 {"email":"linus@shift.dk","primary":true,"verified":true}]""");

        HttpResponse<String> authorize = get("/v1/auth/oidc/github/authorize");
        Map<String, String> query = query(location(authorize));
        assertThat(query).containsEntry("scope", "user:email")
                .containsEntry("client_id", StubProviders.GITHUB_CLIENT_ID)
                .containsEntry("code_challenge_method", "S256");

        String fragment = callback("github", query.get("state"));

        assertThat(sha256Base64Url(STUB.lastTokenRequest.get("code_verifier"))).isEqualTo(query.get("code_challenge"));
        assertThat(verifyWithPublicJwks(token(fragment)).getSubject()).isEqualTo("602");
    }

    @Test
    void anEmailWithoutAnAccountIsRefused() throws Exception {
        // Accounts only come from workforce events; a provider login never creates one.
        gitHubSays(9603, "[{\"email\":\"stranger@example.com\",\"primary\":true,\"verified\":true}]");

        assertThat(login("github")).isEqualTo(FRONTEND_CALLBACK + "#error=no_active_account");
    }

    @Test
    void aDeactivatedEmployeeIsRefused() throws Exception {
        employee(604, 1, "margaret@shift.dk", "Employee", "ACTIVE");
        googleSays("google-604", "margaret@shift.dk", true);
        assertThat(login("google")).contains("#token=");

        employee(604, 2, "margaret@shift.dk", "Employee", "TERMINATED");
        await().atMost(Duration.ofSeconds(20)).until(() -> !accounts.findByEmployeeId(604).orElseThrow().active());

        assertThat(login("google")).isEqualTo(FRONTEND_CALLBACK + "#error=no_active_account");
    }

    @Test
    void anUnverifiedEmailIsRefusedEvenIfItMatchesAnAccount() throws Exception {
        // Otherwise anyone could add a colleague's address to their provider account and log in as them.
        employee(605, 1, "katherine@shift.dk", "Administrator", "ACTIVE");

        googleSays("google-605", "katherine@shift.dk", false);
        assertThat(login("google")).isEqualTo(FRONTEND_CALLBACK + "#error=email_not_verified");

        gitHubSays(9605, "[{\"email\":\"katherine@shift.dk\",\"primary\":true,\"verified\":false}]");
        assertThat(login("github")).isEqualTo(FRONTEND_CALLBACK + "#error=email_not_verified");
    }

    @Test
    void aStateCanBeUsedOnlyOnce() throws Exception {
        employee(606, 1, "barbara@shift.dk", "Employee", "ACTIVE");
        googleSays("google-606", "barbara@shift.dk", true);
        String state = query(location(get("/v1/auth/oidc/google/authorize"))).get("state");

        assertThat(callback("google", state)).contains("#token=");
        assertThat(callback("google", state)).isEqualTo(FRONTEND_CALLBACK + "#error=invalid_state");
    }

    @Test
    void anIdTokenNotSignedByTheProviderIsRefused() throws Exception {
        employee(607, 1, "frances@shift.dk", "Employee", "ACTIVE");
        googleSays("google-607", "frances@shift.dk", true);
        STUB.signWithAttackerKey = true;
        try {
            assertThat(login("google")).isEqualTo(FRONTEND_CALLBACK + "#error=provider_error");
        } finally {
            STUB.signWithAttackerKey = false;
        }
    }

    @Test
    void aLinkedGitHubAccountStillWorksAfterWorkforceChangesTheEmail() throws Exception {
        employee(608, 1, "edsger@shift.dk", "Employee", "ACTIVE");
        gitHubSays(9608, "[{\"email\":\"edsger@shift.dk\",\"primary\":true,\"verified\":true}]");
        assertThat(login("github")).contains("#token=");

        employee(608, 2, "e.dijkstra@shift.dk", "Employee", "ACTIVE");
        await().atMost(Duration.ofSeconds(20))
                .until(() -> accounts.findByEmployeeId(608).orElseThrow().email().equals("e.dijkstra@shift.dk"));

        // GitHub still reports the old email, which no longer matches anyone: only the link finds 608.
        assertThat(verifyWithPublicJwks(token(login("github"))).getSubject()).isEqualTo("608");
    }

    @Test
    void anUnknownProviderIs400ProblemJson() throws Exception {
        HttpResponse<String> response = get("/v1/auth/oidc/facebook/authorize");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
    }

    private String login(String provider) throws Exception {
        String state = query(location(get("/v1/auth/oidc/" + provider + "/authorize"))).get("state");
        return callback(provider, state);
    }

    /** The provider sending the browser back; returns where identity redirects the browser next. */
    private String callback(String provider, String state) throws Exception {
        HttpResponse<String> response = get("/v1/auth/oidc/" + provider + "/callback?code=the-code&state="
                + URLEncoder.encode(state, StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store");
        return location(response).toString();
    }

    private static String token(String frontendRedirect) {
        assertThat(frontendRedirect).startsWith(FRONTEND_CALLBACK + "#token=");
        return frontendRedirect.substring((FRONTEND_CALLBACK + "#token=").length());
    }

    private Jwt verifyWithPublicJwks(String token) throws Exception {
        String jwks = get("/.well-known/jwks.json").body();
        RSAKey publicKey = (RSAKey) JWKSet.parse(jwks).getKeys().get(0);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder.decode(token);
    }

    private static void googleSays(String subject, String email, boolean verified) {
        STUB.googleSubject = subject;
        STUB.googleEmail = email;
        STUB.googleEmailVerified = verified;
    }

    private static void gitHubSays(long userId, String emailsJson) {
        STUB.githubUserId = userId;
        STUB.githubEmailsJson = emailsJson;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return browser.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI location(HttpResponse<?> response) {
        return URI.create(response.headers().firstValue("Location").orElseThrow());
    }

    private static Map<String, String> query(URI uri) {
        return UriComponentsBuilder.fromUri(uri).build().getQueryParams().toSingleValueMap().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> URLDecoder.decode(entry.getValue(), StandardCharsets.UTF_8)));
    }

    private static String sha256Base64Url(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    private void employee(long employeeId, long version, String email, String role, String status) throws Exception {
        String eventType = version == 1 ? "workforce.employee.created.v1" : "workforce.employee.updated.v1";
        Map<String, Object> envelope = Map.of(
                "eventId", UUID.randomUUID().toString(), "eventType", eventType,
                "occurredAt", "2026-11-04T08:00:00Z", "producer", "workforce-service",
                "aggregateId", employeeId, "aggregateVersion", version, "correlationId", "oidc-it",
                "data", Map.of("employeeId", employeeId, "employeeNumber", "EMP-" + employeeId, "userRole", role,
                        "firstName", "Test", "lastName", "Person", "email", email, "employmentStatus", status,
                        "jobRoleIds", java.util.List.of()));
        rabbitTemplate.send(exchange, eventType, MessageBuilder
                .withBody(objectMapper.writeValueAsBytes(envelope))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build());
        await().atMost(Duration.ofSeconds(20)).until(() -> accounts.findByEmployeeId(employeeId)
                .filter(account -> account.aggregateVersion() == version).isPresent());
    }
}
