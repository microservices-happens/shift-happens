package dk.ek.shift_happens.identity.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Consumer topology (contracts/README.md):
 *
 * <ul>
 *   <li>one durable queue per consumer, bound to exactly the routing keys it handles;</li>
 *   <li>three attempts, then the message is republished to the dead-letter exchange;</li>
 *   <li>no infinite requeue.</li>
 * </ul>
 */
@Configuration
public class RabbitTopology {

    /** workforce.employee.created.v1, .updated.v1 and .deleted.v1. */
    private static final String EMPLOYEE_EVENTS = "workforce.employee.*.v1";

    @Bean
    TopicExchange eventsExchange(@Value("${app.events.exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }

    @Bean
    TopicExchange deadLetterExchange(@Value("${app.events.dead-letter-exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }

    @Bean
    Queue accountsQueue(@Value("${app.rabbit.queue}") String name,
                        @Value("${app.events.dead-letter-exchange}") String deadLetterExchange) {
        return QueueBuilder.durable(name).deadLetterExchange(deadLetterExchange).build();
    }

    @Bean
    Binding accountsBinding(Queue accountsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(accountsQueue).to(eventsExchange).with(EMPLOYEE_EVENTS);
    }

    @Bean
    Queue accountsDeadLetterQueue(@Value("${app.rabbit.queue}") String name) {
        return QueueBuilder.durable(name + ".dlq").build();
    }

    @Bean
    Binding accountsDeadLetterBinding(Queue accountsDeadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(accountsDeadLetterQueue).to(deadLetterExchange).with("#");
    }

    @Bean
    Jackson2JsonMessageConverter jacksonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            RabbitTemplate rabbitTemplate,
            @Value("${app.events.dead-letter-exchange}") String deadLetterExchange) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(converter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                // Keep the original routing key; the DLQ is bound with "#".
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, deadLetterExchange, null))
                .build());
        return factory;
    }
}
