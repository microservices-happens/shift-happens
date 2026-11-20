package dk.ek.shift_happens.emailfunction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The function has no web starter, so Boot's Jackson auto-configuration does not apply. The envelope is
 * read as a tree; nothing is serialized, so a plain {@link ObjectMapper} is enough.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
