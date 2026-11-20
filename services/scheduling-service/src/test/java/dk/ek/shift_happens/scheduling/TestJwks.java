package dk.ek.shift_happens.scheduling;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for identity-service in the integration tests: one RSA key, a JWKS endpoint and a token
 * minter, so both roles validate a real signature instead of a mocked decoder.
 *
 * <p>The key lives in memory and the endpoint is a local {@link HttpServer} on a free port, which is the
 * "test key until Identity exists" that docs/architecture.md §4.6 allows.
 */
final class TestJwks {

    static final String ISSUER = "shift-happens-identity";
    /** Test-only request header: "send this request without a token"; it never reaches the service. */
    static final String ANONYMOUS = "X-Test-Anonymous";

    private static final AtomicInteger FETCHES = new AtomicInteger();
    private static final RSAKey KEY = generateKey();
    private static final HttpServer SERVER = startServer();
    private static final JwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));

    private TestJwks() {
    }

    static String jwksUrl() {
        return "http://127.0.0.1:" + SERVER.getAddress().getPort() + "/.well-known/jwks.json";
    }

    /**
     * Makes every request of this client carry a fresh token of {@code role}, unless the request sets its
     * own Authorization header or the {@link #ANONYMOUS} marker. Safe to call before every test.
     */
    static void authorizeByDefault(TestRestTemplate http, String role) {
        List<ClientHttpRequestInterceptor> interceptors = http.getRestTemplate().getInterceptors();
        if (interceptors.stream().noneMatch(DefaultBearer.class::isInstance)) {
            interceptors.add(new DefaultBearer(role));
            http.getRestTemplate().setInterceptors(interceptors);
        }
    }

    /** Headers of a request that must reach the service without any token. */
    static HttpHeaders anonymous() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(ANONYMOUS, "true");
        return headers;
    }

    /** Headers of a request with exactly this token instead of the default one. */
    static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private record DefaultBearer(String role) implements ClientHttpRequestInterceptor {

        @Override
        public org.springframework.http.client.ClientHttpResponse intercept(
                org.springframework.http.HttpRequest request, byte[] body,
                org.springframework.http.client.ClientHttpRequestExecution execution) throws IOException {
            boolean anonymous = request.getHeaders().remove(ANONYMOUS) != null;
            if (!anonymous && !request.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
                request.getHeaders().setBearerAuth(token(900, role));
            }
            return execution.execute(request, body);
        }
    }

    /** How many times the JWKS endpoint was fetched: the services must cache it, not ask per request. */
    static int fetches() {
        return FETCHES.get();
    }

    /** One line per role, so a test reads as "who is calling". Employee 900 owns no assignment. */
    static String administrator() {
        return token(900, "Administrator");
    }

    static String manager() {
        return token(900, "Manager");
    }

    static String employee(long employeeId) {
        return token(employeeId, "Employee");
    }

    /** An RS256 token with exactly the claims of contracts/README.md. */
    static String token(long employeeId, String role) {
        return token(employeeId, role, ISSUER, Instant.now().plus(Duration.ofMinutes(5)));
    }

    /** Correctly signed, but expired an hour ago (beyond the 60 s clock skew): the service must reject it. */
    static String expiredToken(long employeeId, String role) {
        return token(employeeId, role, ISSUER, Instant.now().minus(Duration.ofHours(1)));
    }

    /** Correctly signed, but not issued by identity-service: the service must reject it. */
    static String tokenFromAnotherIssuer(long employeeId, String role) {
        return token(employeeId, role, "someone-else", Instant.now().plus(Duration.ofMinutes(5)));
    }

    private static String token(long employeeId, String role, String issuer, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("employee" + employeeId + "@shift.dk")
                .issuedAt(expiresAt.minus(Duration.ofMinutes(5)))
                .expiresAt(expiresAt)
                .claim("employeeId", employeeId)
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY.getKeyID()).build();
        return ENCODER.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    /** Same shape, but signed with another key: the service must reject it. */
    static String tokenSignedByAnotherKey(long employeeId, String role) {
        RSAKey other = generateKey();
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(other)));
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject("employee" + employeeId + "@shift.dk")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(5)))
                .claim("employeeId", employeeId)
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(other.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static RSAKey generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID("it-key")
                    .build();
        } catch (GeneralSecurityException cannotGenerate) {
            throw new IllegalStateException("cannot create the test RSA key", cannotGenerate);
        }
    }

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/.well-known/jwks.json", exchange -> {
                FETCHES.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException cannotStart) {
            throw new IllegalStateException("cannot start the test JWKS endpoint", cannotStart);
        }
    }
}
