package dk.ek.shift_happens.workforce.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.ExchangeBuilder;

/**
 * Exchange topology. Both exchanges are topic exchanges with the same names in every service, so
 * declarations are idempotent (contracts/README.md).
 */
@Configuration
public class RabbitTopology {

    @Bean
    TopicExchange eventsExchange(@Value("${app.events.exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }

    @Bean
    TopicExchange deadLetterExchange(@Value("${app.events.dead-letter-exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }

    @Bean
    Jackson2JsonMessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
