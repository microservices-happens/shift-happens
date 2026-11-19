package dk.ek.shift_happens.leave.infrastructure.messaging;

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
 * Consumer topology of the command role (contracts/README.md):
 *
 * <ul>
 *   <li>one durable queue per consumer, bound to exactly the routing keys it handles;</li>
 *   <li>three attempts, then the message is republished to the dead-letter exchange;</li>
 *   <li>no infinite requeue.</li>
 * </ul>
 *
 * <p>The saga replies (scheduling.leave-release.*) are bound by the saga ticket, not here.
 */
@Configuration
@Profile("command")
public class LeaveCommandTopology {

    static final String EMPLOYEE_CREATED = "workforce.employee.created.v1";
    static final String EMPLOYEE_UPDATED = "workforce.employee.updated.v1";
    static final String EMPLOYEE_DELETED = "workforce.employee.deleted.v1";

    /**
     * Dead-lettered messages (rejected by the container or republished by the recoverer) leave with the queue
     * name as routing key, so the DLQ is bound to exactly that key. The queue's {@code x-dead-letter-routing-key}
     * is a belt-and-braces backstop for messages the broker dead-letters itself (reject without requeue, TTL);
     * the retry advice republishes through the recoverer first, so the backstop is not reachable in tests.
     */
    @Bean
    Queue leaveCommandQueue(@Value("${app.rabbit.queue}") String name,
                            @Value("${app.events.dead-letter-exchange}") String deadLetterExchange) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(name)
                .build();
    }

    @Bean
    Binding leaveCommandEmployeeCreatedBinding(Queue leaveCommandQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(leaveCommandQueue).to(eventsExchange).with(EMPLOYEE_CREATED);
    }

    @Bean
    Binding leaveCommandEmployeeUpdatedBinding(Queue leaveCommandQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(leaveCommandQueue).to(eventsExchange).with(EMPLOYEE_UPDATED);
    }

    @Bean
    Binding leaveCommandEmployeeDeletedBinding(Queue leaveCommandQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(leaveCommandQueue).to(eventsExchange).with(EMPLOYEE_DELETED);
    }

    @Bean
    Queue leaveCommandDeadLetterQueue(@Value("${app.rabbit.queue}") String name) {
        return QueueBuilder.durable(name + ".dlq").build();
    }

    @Bean
    Binding leaveCommandDeadLetterBinding(Queue leaveCommandDeadLetterQueue, TopicExchange deadLetterExchange,
                                          @Value("${app.rabbit.queue}") String name) {
        return BindingBuilder.bind(leaveCommandDeadLetterQueue).to(deadLetterExchange).with(name);
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter,
            RabbitTemplate rabbitTemplate,
            @Value("${app.events.dead-letter-exchange}") String deadLetterExchange,
            @Value("${app.rabbit.queue}") String queueName) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(converter);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                // Republish with the queue name as routing key (without an explicit key spring-amqp would use
                // "error." + the original key); the DLQ is bound with exactly that key.
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, deadLetterExchange, queueName))
                .build());
        return factory;
    }
}
