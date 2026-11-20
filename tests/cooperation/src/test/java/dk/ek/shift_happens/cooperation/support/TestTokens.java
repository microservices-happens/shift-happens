package dk.ek.shift_happens.cooperation.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;

/**
 * A stand-in for identity-service in a cooperation test that does not start it: one RSA key, a JWKS
 * document served on the test network, and a token minter with the claims of contracts/README.md.
 *
 * <p>The services under test validate these tokens exactly as they validate identity's: they fetch the
 * JWKS from {@code JWT_JWKS_URL} and check the RS256 signature, {@code iss} and {@code exp}. A test that
 * does start identity-service (EmployeeReplicationIT) logs in for a real token instead.
 */
public final class TestTokens {

    public static final String ISSUER = "shift-happens-identity";
    /** What a service on the test network gets as JWT_JWKS_URL when {@link #jwksServer} is running. */
    public static final String JWKS_URL = "http://jwks/.well-known/jwks.json";

    private static final String KEY_ID = "cooperation-key";
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final KeyPair KEY = generateKey();

    private TestTokens() {
    }

    /** Creates (but does not start) a web server that serves the JWKS at {@link #JWKS_URL}. */
    public static GenericContainer<?> jwksServer(Network network) {
        return new GenericContainer<>(DockerImageName.parse("nginx:1.27-alpine"))
                .withNetwork(network)
                .withNetworkAliases("jwks")
                .withExposedPorts(80)
                .withCopyToContainer(Transferable.of(jwks()), "/usr/share/nginx/html/.well-known/jwks.json");
    }

    /** An RS256 token for this employee and role (Administrator, Manager or Employee), valid for 10 minutes. */
    public static String token(long employeeId, String role) {
        long now = Instant.now().getEpochSecond();
        String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + KEY_ID + "\"}";
        String claims = "{\"iss\":\"" + ISSUER + "\",\"sub\":\"" + employeeId + "\",\"employeeId\":" + employeeId
                + ",\"role\":\"" + role + "\",\"iat\":" + now + ",\"exp\":" + (now + 600) + "}";
        String signingInput = encode(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encode(claims.getBytes(StandardCharsets.UTF_8));
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(KEY.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + encode(signature.sign());
        } catch (GeneralSecurityException cannotSign) {
            throw new IllegalStateException("cannot sign the test token", cannotSign);
        }
    }

    private static String jwks() {
        RSAPublicKey publicKey = (RSAPublicKey) KEY.getPublic();
        return "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\"" + KEY_ID + "\",\"n\":\""
                + encode(unsigned(publicKey.getModulus().toByteArray())) + "\",\"e\":\""
                + encode(unsigned(publicKey.getPublicExponent().toByteArray())) + "\"}]}";
    }

    /** JWK integers are unsigned big-endian: drop the sign byte BigInteger adds. */
    private static byte[] unsigned(byte[] twosComplement) {
        if (twosComplement.length > 1 && twosComplement[0] == 0) {
            byte[] trimmed = new byte[twosComplement.length - 1];
            System.arraycopy(twosComplement, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return twosComplement;
    }

    private static String encode(byte[] bytes) {
        return BASE64_URL.encodeToString(bytes);
    }

    private static KeyPair generateKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException cannotGenerate) {
            throw new IllegalStateException("cannot create the test RSA key", cannotGenerate);
        }
    }
}
