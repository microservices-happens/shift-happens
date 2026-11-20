package dk.ek.shift_happens.workforce.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT validation and role rules (P1.7), copied per service on purpose: there is no shared library.
 *
 * <p>Every service validates the token itself (contracts/README.md): RS256 signature against identity's
 * JWKS at {@code JWT_JWKS_URL}, plus {@code iss} and {@code exp}. The {@code role} claim
 * (Administrator|Manager|Employee) becomes {@code ROLE_<role>}.
 *
 * <p>Role rules, from contracts/README.md and contracts/openapi/workforce.yaml:
 * <ul>
 *   <li>{@code GET /health} is public (gateway, Compose and Kubernetes probes).</li>
 *   <li>Every other {@code GET} needs any valid token.</li>
 *   <li>Every write needs Administrator or Manager.</li>
 * </ul>
 * No token or an invalid one is a 401, a valid token with the wrong role is a 403, both as problem+json.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint unauthorized = unauthorized(objectMapper);
        AccessDeniedHandler forbidden = forbidden(objectMapper);
        // CSRF stays enabled: it protects cookie-based sessions, and this API issues no cookie. The
        // stateless, token-only REST API is exempt, so a write without a token is a 401 and not a CSRF 403.
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/v1/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/health", "/health/**", "/error").permitAll()
                        .requestMatchers(HttpMethod.GET).authenticated()
                        .anyRequest().hasAnyRole("Administrator", "Manager"))
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roles()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden));
        return http.build();
    }

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
    private static JwtAuthenticationConverter roles() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    /** 401 with the standard WWW-Authenticate header and a problem+json body. */
    private static AuthenticationEntryPoint unauthorized(ObjectMapper objectMapper) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            bearer.commence(request, response, exception);
            writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED, "unauthorized",
                    "A valid bearer token is required");
        };
    }

    /** 403 with a problem+json body. */
    private static AccessDeniedHandler forbidden(ObjectMapper objectMapper) {
        BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();
        return (request, response, exception) -> {
            bearer.handle(request, response, exception);
            writeProblem(response, objectMapper, HttpStatus.FORBIDDEN, "forbidden", "Role not allowed");
        };
    }

    private static void writeProblem(HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status,
                                     String type, String detail) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "https://shift-happens.local/problems/" + type);
        problem.put("title", status.getReasonPhrase());
        problem.put("status", status.value());
        problem.put("detail", detail);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
