package dk.ek.shift_happens.scheduling.infrastructure.readmodel;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
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
import org.springframework.context.annotation.Profile;

/**
 * Consumer topology of the query role (contracts/README.md): one durable queue bound to exactly the
 * routing keys the read model is built from, three attempts, then the dead-letter exchange.
 *
 * <p>scheduling.* is the service's own event stream, workforce.employee.* is the replica the schedule
 * shows names and job roles from, and ai.replacement.suggested.v1 is the ranking next to an understaffed
 * shift.
 */
@Configuration
@Profile("query")
class ProjectionTopology {

    /** Topic wildcards match one word per {@code *}, so the whole event stream needs {@code #}. */
    private static final String SCHEDULING_EVENTS = "scheduling.#";
    private static final String EMPLOYEE_EVENTS = "workforce.employee.#";
    private static final String AI_SUGGESTIONS = "ai.replacement.suggested.v1";

    @Bean
    Queue schedulingProjectionQueue(@Value("${app.rabbit.queue}") String name,
                                    @Value("${app.events.dead-letter-exchange}") String deadLetterExchange) {
        return QueueBuilder.durable(name).deadLetterExchange(deadLetterExchange).build();
    }

    @Bean
    Binding schedulingProjectionBinding(Queue schedulingProjectionQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(schedulingProjectionQueue).to(eventsExchange).with(SCHEDULING_EVENTS);
    }

    @Bean
    Binding schedulingProjectionEmployeeBinding(Queue schedulingProjectionQueue,
                                                TopicExchange eventsExchange) {
        return BindingBuilder.bind(schedulingProjectionQueue).to(eventsExchange).with(EMPLOYEE_EVENTS);
    }

    @Bean
    Binding schedulingProjectionSuggestionBinding(Queue schedulingProjectionQueue,
                                                  TopicExchange eventsExchange) {
        return BindingBuilder.bind(schedulingProjectionQueue).to(eventsExchange).with(AI_SUGGESTIONS);
    }

    @Bean
    Queue schedulingProjectionDeadLetterQueue(@Value("${app.rabbit.queue}") String name) {
        return QueueBuilder.durable(name + ".dlq").build();
    }

    @Bean
    Binding schedulingProjectionDeadLetterBinding(Queue schedulingProjectionDeadLetterQueue,
                                                  TopicExchange deadLetterExchange) {
        return BindingBuilder.bind(schedulingProjectionDeadLetterQueue).to(deadLetterExchange).with("#");
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
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, deadLetterExchange, null))
                .build());
        return factory;
    }
}
