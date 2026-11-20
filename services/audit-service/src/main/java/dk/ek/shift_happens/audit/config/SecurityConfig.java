package dk.ek.shift_happens.audit.config;

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
 * <p>Every request is authenticated against identity's JWKS ({@code JWT_JWKS_URL}, cached) plus
 * {@code iss} and {@code exp}. The audit log is the one read that is restricted to the Administrator role
 * (contracts/README.md). Everything else in this service is behind a token as well.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint unauthorized = unauthorized(objectMapper);
        AccessDeniedHandler forbidden = forbidden(objectMapper);
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/v1/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/health", "/health/**", "/error").permitAll()
                        // Swagger UI renders the contract; like identity it is open locally.
                        .requestMatchers(HttpMethod.GET, "/swagger-ui.html", "/swagger-ui/**",
                                "/v3/api-docs/**", "/openapi/**").permitAll()
                        // contracts/README.md: /v1/auditlogs is Administrator only.
                        .requestMatchers("/v1/auditlogs", "/v1/auditlogs/**").hasRole("Administrator")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(roles()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden));
        return http.build();
    }

    /** Nimbus fetches the JWKS once per process and caches it (P3.7); this service starts without identity. */
    @Bean
    JwtDecoder jwtDecoder(@Value("${app.security.jwks-url}") String jwksUrl,
                          @Value("${app.security.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUrl).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    private static JwtAuthenticationConverter roles() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static AuthenticationEntryPoint unauthorized(ObjectMapper objectMapper) {
        BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();
        return (request, response, exception) -> {
            bearer.commence(request, response, exception);
            writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED, "unauthorized",
                    "A valid bearer token is required");
        };
    }

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
