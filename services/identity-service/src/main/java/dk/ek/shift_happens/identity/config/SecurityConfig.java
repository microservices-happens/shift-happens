package dk.ek.shift_happens.identity.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import dk.ek.shift_happens.identity.application.TokenIssuer;
import dk.ek.shift_happens.identity.infrastructure.security.JwtTokenIssuer;
import dk.ek.shift_happens.identity.infrastructure.security.PepperedPasswordEncoder;
import dk.ek.shift_happens.identity.infrastructure.security.SigningKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.time.Clock;
import java.time.Duration;

/**
 * Identity signs the JWT and also validates it on its own protected endpoints, with the same
 * public key it publishes at /.well-known/jwks.json. Login, JWKS and /health are public.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health", "/health/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.GET, "/v1/auth/oidc/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> { }))
                .build();
    }

    @Bean
    RSAKey signingKey(@Value("${app.jwt.private-key-path}") String privateKeyPath) {
        return SigningKey.load(privateKeyPath);
    }

    /** Public half only: this is what every other service fetches through JWT_JWKS_URL. */
    @Bean
    JWKSet publicJwkSet(RSAKey signingKey) {
        return new JWKSet(signingKey.toPublicJWK());
    }

    @Bean
    TokenIssuer tokenIssuer(RSAKey signingKey,
                            @Value("${app.jwt.issuer}") String issuer,
                            @Value("${app.jwt.ttl}") Duration ttl) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(signingKey)));
        return new JwtTokenIssuer(encoder, issuer, ttl, signingKey.getKeyID(), Clock.systemUTC());
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey signingKey, @Value("${app.jwt.issuer}") String issuer) throws JOSEException {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(signingKey.toRSAPublicKey()).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder(@Value("${app.password.pepper}") String pepper) {
        return new PepperedPasswordEncoder(pepper);
    }
}
