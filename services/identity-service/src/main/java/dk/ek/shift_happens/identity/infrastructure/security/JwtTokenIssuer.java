package dk.ek.shift_happens.identity.infrastructure.security;

import dk.ek.shift_happens.identity.application.TokenIssuer;
import dk.ek.shift_happens.identity.domain.Account;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Signs the JWT every service verifies. Claims follow contracts/openapi/identity.yaml:
 * sub, employeeId, role, iss, iat, exp. {@code sub} is the employee id, which stays stable
 * when the email changes.
 */
public class JwtTokenIssuer implements TokenIssuer {

    private final JwtEncoder encoder;
    private final String issuer;
    private final Duration ttl;
    private final String keyId;
    private final Clock clock;

    public JwtTokenIssuer(JwtEncoder encoder, String issuer, Duration ttl, String keyId, Clock clock) {
        this.encoder = encoder;
        this.issuer = issuer;
        this.ttl = ttl;
        this.keyId = keyId;
        this.clock = clock;
    }

    @Override
    public String issueFor(Account account) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(Long.toString(account.employeeId()))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("employeeId", account.employeeId())
                .claim("role", account.role())
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(keyId).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
