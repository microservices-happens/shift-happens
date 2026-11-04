package dk.ek.shift_happens.identity;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import dk.ek.shift_happens.identity.domain.Account;
import dk.ek.shift_happens.identity.domain.AccountRepository;
import dk.ek.shift_happens.identity.infrastructure.security.JwtTokenIssuer;
import dk.ek.shift_happens.identity.infrastructure.security.SigningKey;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Local login (P2.1) end to end inside the service: the account arrives from a workforce event, the
 * password is checked with the pepper, and the JWT verifies with nothing but the public JWKS, which is
 * exactly what every other service will do with JWT_JWKS_URL.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoginIT {

    private static final String ISSUER = "shift-happens-identity";

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
    }

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AccountRepository accounts;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RSAKey signingKey;
    @Value("${app.events.exchange}")
    private String exchange;

    @Test
    void anEmployeeFromWorkforceCanLogInAndTheTokenVerifiesWithThePublicJwks() throws Exception {
        employeeWithPassword(501, "grace@shift.dk", "Manager", "correct horse");

        ResponseEntity<Map> response = login("grace@shift.dk", "correct horse");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = response.getBody();
        assertThat(body).containsEntry("employeeId", 501)
                .containsEntry("email", "grace@shift.dk")
                .containsEntry("roleName", "Manager")
                // Names come from the workforce snapshot, so the frontend keeps its monolith shape.
                .containsEntry("firstName", "Grace")
                .containsEntry("lastName", "Hopper");

        // Verify only with what is public: the JWKS endpoint, as other services will.
        String jwksJson = http.getForObject("/.well-known/jwks.json", String.class);
        JWKSet published = JWKSet.parse(jwksJson);
        RSAKey publicKey = (RSAKey) published.getKeys().get(0);
        assertThat(publicKey.isPrivate()).as("the JWKS must never contain the private key").isFalse();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        Jwt jwt = decoder.decode((String) body.get("token"));

        assertThat(jwt.getSubject()).isEqualTo("501");
        assertThat(jwt.getClaimAsString("role")).isEqualTo("Manager");
        assertThat(((Number) jwt.getClaim("employeeId")).longValue()).isEqualTo(501);
        assertThat(jwt.getHeaders()).containsEntry("kid", publicKey.getKeyID());
    }

    @Test
    void aWrongPasswordIs401ProblemJson() {
        employeeWithPassword(502, "linus@shift.dk", "Employee", "correct horse");

        ResponseEntity<Map> response = login("linus@shift.dk", "wrong");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody()).doesNotContainKey("token");
    }

    @Test
    void aDeactivatedEmployeeCanNoLongerLogIn() {
        // README "Done when": deactivating the employee in workforce must cut off login.
        employeeWithPassword(503, "margaret@shift.dk", "Employee", "correct horse");
        assertThat(login("margaret@shift.dk", "correct horse").getStatusCode()).isEqualTo(HttpStatus.OK);

        publishEmployee(503, 2, "margaret@shift.dk", "Employee", "TERMINATED");
        await().atMost(Duration.ofSeconds(20))
                .until(() -> !accounts.findByEmployeeId(503).orElseThrow().active());

        assertThat(login("margaret@shift.dk", "correct horse").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void aMissingPasswordIs400() {
        ResponseEntity<Map> response = http.postForEntity("/v1/auth/login",
                Map.of("email", "someone@shift.dk"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void protectedEndpointsRejectMissingForgedAndExpiredTokens() throws Exception {
        String path = "/v1/auth/accounts/1/password";

        String valid = issuer(signingKey, Clock.systemUTC()).issueFor(anyAccount());
        String forged = issuer(SigningKey.load(""), Clock.systemUTC()).issueFor(anyAccount());
        String expired = issuer(signingKey, Clock.fixed(Instant.now().minus(Duration.ofDays(2)), ZoneOffset.UTC))
                .issueFor(anyAccount());

        assertThat(put(path, null)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(put(path, forged)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(put(path, expired)).isEqualTo(HttpStatus.UNAUTHORIZED);
        // Control: a valid token passes authentication (the endpoint itself arrives with P2.4).
        assertThat(put(path, valid)).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private ResponseEntity<Map> login(String email, String password) {
        return http.postForEntity("/v1/auth/login", Map.of("email", email, "password", password), Map.class);
    }

    private HttpStatus put(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return HttpStatus.valueOf(http.exchange(path, HttpMethod.PUT,
                new HttpEntity<>("{\"password\":\"new-password\"}", headers), String.class).getStatusCode().value());
    }

    private static JwtTokenIssuer issuer(RSAKey key, Clock clock) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(key)));
        return new JwtTokenIssuer(encoder, ISSUER, Duration.ofHours(1), key.getKeyID(), clock);
    }

    private static Account anyAccount() {
        return new Account(1L, 1, "EMP-1", "x@shift.dk", "X", "Y", "Administrator", true, 1, null);
    }

    /** The account must come from workforce; only the password is set directly (P2.4 adds the endpoint). */
    private void employeeWithPassword(long employeeId, String email, String role, String password) {
        publishEmployee(employeeId, 1, email, role, "ACTIVE");
        await().atMost(Duration.ofSeconds(20))
                .until(() -> accounts.findByEmployeeId(employeeId).isPresent());
        jdbc.update("UPDATE account SET password_hash = ? WHERE employee_id = ?",
                passwordEncoder.encode(password), employeeId);
    }

    private void publishEmployee(long employeeId, long version, String email, String role, String status) {
        String eventType = version == 1 ? "workforce.employee.created.v1" : "workforce.employee.updated.v1";
        String body = """
                {"eventId":"%s","eventType":"%s","occurredAt":"2026-11-04T08:00:00Z",
                 "producer":"workforce-service","aggregateId":%d,"aggregateVersion":%d,
                 "correlationId":"login-it","causationId":null,
                 "data":{"employeeId":%d,"employeeNumber":"EMP-%d","userRole":"%s",
                         "firstName":"Grace","lastName":"Hopper","email":"%s","employmentStatus":"%s",
                         "jobRoleIds":[]}}
                """.formatted(UUID.randomUUID(), eventType, employeeId, version, employeeId, employeeId, role,
                email, status);

        rabbitTemplate.send(exchange, eventType, MessageBuilder
                .withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .build());
    }
}
