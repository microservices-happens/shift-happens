package dk.ek.shift_happens.identity.infrastructure.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/**
 * The RS256 key pair that signs every Shift Happens JWT.
 *
 * <p>Read from a PKCS#8 PEM file ({@code JWT_PRIVATE_KEY_PATH}, a mounted secret in Kubernetes), so
 * tokens survive a restart and every replica signs with the same key. Without a path a key is
 * generated in memory: fine for local development, but tokens become invalid on restart.
 */
public final class SigningKey {

    private static final Logger log = LoggerFactory.getLogger(SigningKey.class);

    private SigningKey() {
    }

    public static RSAKey load(String privateKeyPath) {
        try {
            KeyPair pair = privateKeyPath == null || privateKeyPath.isBlank()
                    ? generate()
                    : readPem(Path.of(privateKeyPath));
            RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey(pair.getPrivate())
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .build();
            // The thumbprint is a stable key id: the same key file always gets the same kid.
            return new RSAKey.Builder(key).keyID(key.computeThumbprint().toString()).build();
        } catch (GeneralSecurityException | JOSEException e) {
            throw new IllegalStateException("Could not load the JWT signing key", e);
        }
    }

    private static KeyPair generate() throws GeneralSecurityException {
        log.warn("JWT_PRIVATE_KEY_PATH is not set: using a generated key. Tokens are invalid after a restart.");
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static KeyPair readPem(Path path) throws GeneralSecurityException {
        String pem;
        try {
            pem = Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read JWT_PRIVATE_KEY_PATH " + path, e);
        }
        if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
            throw new IllegalStateException(path + " is not a PKCS#8 PEM key (BEGIN PRIVATE KEY)");
        }
        String base64 = pem.replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "").replaceAll("\\s", "");
        KeyFactory factory = KeyFactory.getInstance("RSA");
        RSAPrivateCrtKey privateKey = (RSAPrivateCrtKey) factory.generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        BigInteger modulus = privateKey.getModulus();
        RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(modulus, privateKey.getPublicExponent()));
        return new KeyPair(publicKey, privateKey);
    }
}
