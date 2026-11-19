package dk.ek.shift_happens.leave.infrastructure.messaging;

import dk.ek.shift_happens.leave.application.ProjectionService;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Consumer topology of the query role (contracts/README.md): the durable queue {@code leave.projection} bound to
 * exactly the routing keys the projection handles (no wildcards), three attempts, then the message is republished to
 * the dead-letter exchange with the queue name as routing key and lands in {@code <queue>.dlq}. No infinite requeue.
 */
@Configuration
@Profile("query")
public class LeaveQueryTopology {

    /** The exact bindings of the leave.projection queue (contracts/events/catalog.md). */
    static final List<String> BOUND_EVENT_TYPES = List.of(
            ProjectionService.EMPLOYEE_CREATED,
            ProjectionService.EMPLOYEE_UPDATED,
            ProjectionService.EMPLOYEE_DELETED,
            ProjectionService.REQUEST_SUBMITTED,
            ProjectionService.REQUEST_APPROVAL_STARTED,
            ProjectionService.REQUEST_APPROVED,
            ProjectionService.REQUEST_REJECTED,
            ProjectionService.REQUEST_CANCELLED,
            ProjectionService.LEDGER_ENTRY_RECORDED,
            ProjectionService.TYPE_CREATED,
            ProjectionService.TYPE_UPDATED,
            ProjectionService.TYPE_DELETED);

    /**
     * Dead letters leave with the queue name as routing key, so the DLQ is bound to exactly that key; the queue's
     * {@code x-dead-letter-routing-key} is the same backstop for messages the broker dead-letters itself.
     */
    @Bean
    Queue leaveProjectionQueue(@Value("${app.rabbit.queue}") String name,
                               @Value("${app.events.dead-letter-exchange}") String deadLetterExchange) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(name)
                .build();
    }

    @Bean
    Declarables leaveProjectionBindings(Queue leaveProjectionQueue, TopicExchange eventsExchange) {
        List<Declarable> bindings = new ArrayList<>();
        for (String eventType : BOUND_EVENT_TYPES) {
            Binding binding = BindingBuilder.bind(leaveProjectionQueue).to(eventsExchange).with(eventType);
            bindings.add(binding);
        }
        return new Declarables(bindings);
    }

    @Bean
    Queue leaveProjectionDeadLetterQueue(@Value("${app.rabbit.queue}") String name) {
        return QueueBuilder.durable(name + ".dlq").build();
    }

    @Bean
    Binding leaveProjectionDeadLetterBinding(Queue leaveProjectionDeadLetterQueue, TopicExchange deadLetterExchange,
                                             @Value("${app.rabbit.queue}") String name) {
        return BindingBuilder.bind(leaveProjectionDeadLetterQueue).to(deadLetterExchange).with(name);
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
                // Without an explicit key spring-amqp would republish with "error." + the original key.
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, deadLetterExchange, queueName))
                .build());
        return factory;
    }
}
