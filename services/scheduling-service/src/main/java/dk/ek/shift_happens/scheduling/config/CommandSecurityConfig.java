package dk.ek.shift_happens.scheduling.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Role rules of the command role (P1.7). The token itself is validated by the decoder of
 * {@link JwtConfig}.
 *
 * <p>Rules, from contracts/README.md and contracts/openapi/scheduling.yaml:
 * <ul>
 *   <li>{@code GET /health} is public (gateway, Compose and Kubernetes probes).</li>
 *   <li>Every other {@code GET} needs any valid token (the contract has none on this role).</li>
 *   <li>Every write needs Administrator or Manager.</li>
 *   <li>{@code POST /v1/shiftswaps} is also open to an Employee, but only for their own assignment
 *       ({@code employeeFromId} = JWT {@code employeeId}); that half is checked in
 *       {@code ShiftSwapController}, because it depends on the request body.</li>
 * </ul>
 * No token or an invalid one is a 401, a valid token with the wrong role is a 403, both as problem+json.
 */
@Configuration
@Profile("command")
public class CommandSecurityConfig {

    @Bean
    SecurityFilterChain commandFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        AuthenticationEntryPoint unauthorized = unauthorized(objectMapper);
        AccessDeniedHandler forbidden = forbidden(objectMapper);
        // CSRF stays enabled, because it protects cookie-based sessions and this service issues no
        // cookie. The stateless REST API is exempt, so a write without a token is a 401 and not a CSRF 403.
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/v1/**"))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/health", "/health/**", "/error", "/prometheus", "/metrics").permitAll()
                        .requestMatchers(HttpMethod.GET).authenticated()
                        .requestMatchers(HttpMethod.POST, "/v1/shiftswaps")
                        .hasAnyRole("Administrator", "Manager", "Employee")
                        .anyRequest().hasAnyRole("Administrator", "Manager"))
                .oauth2ResourceServer(server -> server
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(JwtConfig.roles()))
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthorized)
                        .accessDeniedHandler(forbidden));
        return http.build();
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
