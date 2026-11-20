package dk.ek.shift_happens.identity;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Fake Google and GitHub on localhost, so the tests never call the real providers. Google's ID token
 * is signed with a test RSA key whose public half is served as the stub's JWKS, exactly like Google.
 * Built on the JDK's HttpServer to avoid a new test dependency.
 */
final class StubProviders {

    static final String GOOGLE_CLIENT_ID = "google-client-it";
    static final String GITHUB_CLIENT_ID = "github-client-it";
    static final String GOOGLE_ISSUER = "https://accounts.google.com";

    private final HttpServer server;
    private final RSAKey googleKey;
    private final RSAKey attackerKey;

    // What the next token exchange returns; set by each test.
    volatile String googleSubject;
    volatile String googleEmail;
    volatile boolean googleEmailVerified;
    volatile boolean signWithAttackerKey;
    volatile long githubUserId;
    volatile String githubEmailsJson;
    /** The form of the last token request, to check the PKCE verifier that identity sent. */
    volatile Map<String, String> lastTokenRequest;

    StubProviders() {
        try {
            googleKey = new RSAKeyGenerator(2048).keyID("google-it").generate();
            attackerKey = new RSAKeyGenerator(2048).keyID("google-it").generate();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/google/jwks", exchange ->
                respond(exchange, new JWKSet(googleKey.toPublicJWK()).toString(true)));
        server.createContext("/google/token", exchange -> {
            lastTokenRequest = form(exchange);
            respond(exchange, "{\"access_token\":\"google-access\",\"token_type\":\"Bearer\",\"id_token\":\""
                    + idToken() + "\"}");
        });
        server.createContext("/github/token", exchange -> {
            lastTokenRequest = form(exchange);
            respond(exchange, "{\"access_token\":\"github-access\",\"token_type\":\"bearer\",\"scope\":\"user:email\"}");
        });
        server.createContext("/github/user", exchange -> respondIfAuthorized(exchange,
                "{\"id\":" + githubUserId + ",\"login\":\"someone\"}"));
        server.createContext("/github/emails", exchange -> respondIfAuthorized(exchange, githubEmailsJson));
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private String idToken() {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(GOOGLE_ISSUER)
                .audience(GOOGLE_CLIENT_ID)
                .subject(googleSubject)
                .claim("email", googleEmail)
                .claim("email_verified", googleEmailVerified)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build();
        RSAKey key = signWithAttackerKey ? attackerKey : googleKey;
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> form(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return Arrays.stream(body.split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                        pair -> pair.length > 1 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : ""));
    }

    private static void respondIfAuthorized(HttpExchange exchange, String json) throws IOException {
        if (!"Bearer github-access".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        respond(exchange, json);
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
