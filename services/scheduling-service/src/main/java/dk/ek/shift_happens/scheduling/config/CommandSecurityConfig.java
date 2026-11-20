package dk.ek.shift_happens.scheduling.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Placeholder for the command role.
 *
 * <p>P2.7, P2.9 and the saga of P3.1 are tested without tokens today, and the JWT rules that
 * contracts/README.md describes for writes (Administrator or Manager, Employees only for their own swap
 * request) are P1.7. Until that task lands, this chain keeps the write API open instead of silently
 * falling back to a random generated password, which would break the gateway and every command test.
 *
 * <p>It is deliberately explicit: when P1.7 arrives, this class is replaced and the integration tests
 * start sending real tokens.
 */
@Configuration
@Profile("command")
public class CommandSecurityConfig {

    @Bean
    SecurityFilterChain commandFilterChain(HttpSecurity http) throws Exception {
        // CSRF stays enabled, because it protects cookie-based sessions and this service issues no
        // cookie. The stateless REST API is exempt, which avoids a false 403 for every write.
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/v1/**"))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }
}
