package dk.ek.shift_happens.identity;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * Token minter for identity's slice tests, with the same API as {@code TestJwks} in the other services.
 *
 * <p>identity verifies tokens with its own signing key ({@code JWT_PRIVATE_KEY_PATH}), so instead of a JWKS
 * endpoint this class writes a test key to a temporary PKCS#8 file: point {@code app.jwt.private-key-path}
 * at {@link #privateKeyPath()} and the service signs and verifies with the key the tokens here use.
 */
final class TestJwks {

    static final String ISSUER = "shift-happens-identity";

    private static final KeyPair PAIR = generatePair();
    private static final RSAKey KEY = rsaKey(PAIR, "test-key");
    private static final Path PRIVATE_KEY = writePem(PAIR);
    private static final JwtEncoder ENCODER = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(KEY)));

    private TestJwks() {
    }

    static String privateKeyPath() {
        return PRIVATE_KEY.toString();
    }

    /** One line per role, so a test reads as "who is calling". */
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
        return token(ENCODER, KEY, employeeId, role, ISSUER, Instant.now().plus(Duration.ofMinutes(5)));
    }

    /** Correctly signed, but expired an hour ago (beyond the 60 s clock skew). */
    static String expiredToken(long employeeId, String role) {
        return token(ENCODER, KEY, employeeId, role, ISSUER, Instant.now().minus(Duration.ofHours(1)));
    }

    /** Correctly signed, but not issued by identity-service. */
    static String tokenFromAnotherIssuer(long employeeId, String role) {
        return token(ENCODER, KEY, employeeId, role, "someone-else", Instant.now().plus(Duration.ofMinutes(5)));
    }

    /** Same shape, but signed with another key. */
    static String tokenSignedByAnotherKey(long employeeId, String role) {
        RSAKey other = rsaKey(generatePair(), "other-key");
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(other)));
        return token(encoder, other, employeeId, role, ISSUER, Instant.now().plus(Duration.ofMinutes(5)));
    }

    private static String token(JwtEncoder encoder, RSAKey key, long employeeId, String role, String issuer,
                                Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("employee" + employeeId + "@shift.dk")
                .issuedAt(expiresAt.minus(Duration.ofMinutes(5)))
                .expiresAt(expiresAt)
                .claim("employeeId", employeeId)
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(key.getKeyID()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private static KeyPair generatePair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException cannotGenerate) {
            throw new IllegalStateException("cannot create the test RSA key", cannotGenerate);
        }
    }

    private static RSAKey rsaKey(KeyPair pair, String keyId) {
        return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                .privateKey((RSAPrivateKey) pair.getPrivate())
                .keyID(keyId)
                .build();
    }

    private static Path writePem(KeyPair pair) {
        try {
            Path file = Files.createTempFile("identity-test-key", ".pem");
            file.toFile().deleteOnExit();
            String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(pair.getPrivate().getEncoded());
            Files.writeString(file, "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n");
            return file;
        } catch (IOException cannotWrite) {
            throw new UncheckedIOException(cannotWrite);
        }
    }
}
