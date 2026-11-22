package dk.ek.shift_happens.workforce.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;

import static org.assertj.core.api.Assertions.assertThat;

/** The names in contracts/README.md are what every service binds to, so they must not drift here. */
class RabbitTopologyTest {

    private final RabbitTopology topology = new RabbitTopology();

    @Test
    void bothExchangesAreDurableTopicsWithTheContractNames() {
        TopicExchange events = topology.eventsExchange("shift-happens.events");
        TopicExchange deadLetters = topology.deadLetterExchange("shift-happens.dlx");

        assertThat(events.getName()).isEqualTo("shift-happens.events");
        assertThat(events.getType()).isEqualTo("topic");
        assertThat(events.isDurable()).isTrue();
        assertThat(deadLetters.getName()).isEqualTo("shift-happens.dlx");
        assertThat(deadLetters.getType()).isEqualTo("topic");
        assertThat(deadLetters.isDurable()).isTrue();
    }

    @Test
    void messagesAreJson() {
        Jackson2JsonMessageConverter converter = topology.jacksonMessageConverter(new ObjectMapper());

        assertThat(converter).isNotNull();
    }
}
