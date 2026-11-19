package dk.ek.shift_happens.scheduling.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * JWT validation and role rules of the query role (P1.7 for this role, needed by P2.11).
 *
 * <p>Every service validates the token itself (contracts/README.md): the signature against identity's
 * JWKS, plus {@code iss} and {@code exp}. The {@code role} claim becomes {@code ROLE_<role>}, and the
 * visibility rules that depend on it live in the query use cases.
 *
 * <p>/health stays open for Compose and the Kubernetes probes, and /graphiql is only served when the dev
 * profile enables it.
 */
@Configuration
@Profile("query")
public class QuerySecurityConfig {

    @Bean
    SecurityFilterChain queryFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/health", "/health/**", "/error", "/graphiql", "/graphiql/**",
                                "/vendor/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(roles())));
        return http.build();
    }

    /** Nimbus fetches the JWKS once and caches it, which is the caching the architecture asks for. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.security.jwks-url}") String jwksUrl,
                          @Value("${app.security.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUrl).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    /** The contract has one role claim, so it maps to one authority: Administrator, Manager or Employee. */
    private static JwtAuthenticationConverter roles() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
