package dk.ek.shift_happens.scheduling.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * JWT validation shared by the command and the query role (P1.7): one codebase, so one decoder.
 *
 * <p>Every service validates the token itself (contracts/README.md): RS256 signature against identity's
 * JWKS at {@code JWT_JWKS_URL}, plus {@code iss} and {@code exp}.
 */
@Configuration
public class JwtConfig {

    /**
     * Nimbus fetches the JWKS on the first token and caches it, so identity is asked once and not per
     * request (P3.7). The fetch is lazy: this service starts without identity being up.
     */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.security.jwks-url}") String jwksUrl,
                          @Value("${app.security.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUrl).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    /** The contract has one role claim, so it maps to one authority: Administrator, Manager or Employee. */
    static JwtAuthenticationConverter roles() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
