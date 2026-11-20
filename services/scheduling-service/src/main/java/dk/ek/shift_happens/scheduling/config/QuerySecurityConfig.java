package dk.ek.shift_happens.scheduling.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Role rules of the query role (P1.7 for this role, needed by P2.11). The token itself is validated by
 * the decoder of {@link JwtConfig}.
 *
 * <p>The {@code role} claim becomes {@code ROLE_<role>}, and the visibility rules that depend on it live
 * in the query use cases.
 *
 * <p>/health stays open for Compose and the Kubernetes probes, and /graphiql is only served when the dev
 * profile enables it.
 */
@Configuration
@Profile("query")
public class QuerySecurityConfig {

    @Bean
    SecurityFilterChain queryFilterChain(HttpSecurity http,
                                         @Value("${spring.graphql.path:/graphql}") String graphqlPath)
            throws Exception {
        // CSRF stays enabled: it protects cookie-based sessions, and this API issues no cookie. Only the
        // stateless GraphQL endpoint is exempt, which is the documented pattern for a token-only API.
        http.csrf(csrf -> csrf.ignoringRequestMatchers(graphqlPath))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/health", "/health/**", "/error", "/graphiql", "/graphiql/**",
                                "/vendor/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.jwtAuthenticationConverter(JwtConfig.roles())));
        return http.build();
    }
}
