
package dk.ek.shift_happens.notification.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.ArrayList;

@Configuration
public class RabbitMQConfig {

    private static final List<String> NOTIFICATION_EVENTS = List.of(
            "scheduling.shift.updated.v1",
            "scheduling.shift.understaffed.v1",
            "scheduling.assignment.created.v1",
            "scheduling.assignment.updated.v1",
            "scheduling.swap.requested.v1",
            "scheduling.swap.approved.v1",
            "scheduling.swap.rejected.v1",
            "leave.request.submitted.v1",
            "leave.request.approved.v1",
            "leave.request.rejected.v1",
            "ai.replacement.suggested.v1"
    );

    @Bean
    public TopicExchange eventExchange(
            @Value("${EVENT_EXCHANGE:shift-happens.events}") String exchangeName) {
        return new TopicExchange(exchangeName, true, false);
    }

    @Bean
    public Queue notificationQueue(
            @Value("${RABBITMQ_QUEUE:notification.events}") String queueName) {
        return new Queue(queueName, true);
    }

    @Bean
    public Declarables notificationBindings(
            Queue notificationQueue,
            TopicExchange eventExchange) {

        List<Binding> bindings = NOTIFICATION_EVENTS.stream()
                .map(eventType -> BindingBuilder
                        .bind(notificationQueue)
                        .to(eventExchange)
                        .with(eventType))
                .toList();

        return new Declarables(new ArrayList<>(bindings));
    }
}
