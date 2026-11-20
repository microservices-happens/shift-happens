package dk.ek.shift_happens.audit.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.audit.application.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * audit.events: one durable queue bound to every business event of the catalog (workforce, scheduling,
 * leave, ai). Email requests are deliberately not audited: they carry a recipient address and no business
 * change. A message is tried three times (1 s, then 2 s apart) and then republished to
 * {@code shift-happens.dlx} with the queue name as routing key, landing in {@code <queue>.dlq}.
 */
@Configuration
class AuditTopology {

    /** The routing keys contracts/events/catalog.md lists for audit.events, in one place. */
    static final List<String> AUDITED_EVENTS = List.of(
            "workforce.employee.created.v1", "workforce.employee.updated.v1", "workforce.employee.deleted.v1",
            "scheduling.shift.created.v1", "scheduling.shift.updated.v1", "scheduling.shift.deleted.v1",
            "scheduling.shift.understaffed.v1",
            "scheduling.assignment.created.v1", "scheduling.assignment.updated.v1",
            "scheduling.assignment.deleted.v1",
            "scheduling.swap.requested.v1", "scheduling.swap.approved.v1", "scheduling.swap.rejected.v1",
            "scheduling.leave-release.completed.v1", "scheduling.leave-release.rejected.v1",
            "leave.request.submitted.v1", "leave.request.approval-started.v1", "leave.request.approved.v1",
            "leave.request.rejected.v1", "leave.request.cancelled.v1",
            "leave.ledger.entry-recorded.v1",
            "ai.replacement.suggested.v1");

    @Bean
    TopicExchange auditEvents(@Value("${app.events.exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }

    @Bean
    TopicExchange auditDeadLetters(@Value("${app.events.dead-letter-exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }

    @Bean
    Queue auditQueue(@Value("${app.rabbit.queue}") String name,
                     @Value("${app.events.dead-letter-exchange}") String dlx) {
        return QueueBuilder.durable(name).deadLetterExchange(dlx).build();
    }

    @Bean
    Queue auditDeadLetterQueue(@Value("${app.rabbit.queue}") String name) {
        return QueueBuilder.durable(name + ".dlq").build();
    }

    @Bean
    Declarables auditBindings(Queue auditQueue, TopicExchange auditEvents) {
        return new Declarables(AUDITED_EVENTS.stream()
                .map(key -> (Declarable) BindingBuilder.bind(auditQueue).to(auditEvents).with(key))
                .toList());
    }

    @Bean
    Binding auditDeadLetterBinding(Queue auditDeadLetterQueue, TopicExchange auditDeadLetters,
                                   @Value("${app.rabbit.queue}") String queueName) {
        return BindingBuilder.bind(auditDeadLetterQueue).to(auditDeadLetters).with(queueName);
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate, StaleDeadLetterBindingRemover staleBindingRemover,
            @Value("${app.events.dead-letter-exchange}") String dlx,
            @Value("${app.rabbit.queue}") String queueName) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        connectionFactory.addConnectionListener(staleBindingRemover);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 2.0, 10000)
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, dlx, queueName))
                .build());
        return factory;
    }

    @Bean
    StaleDeadLetterBindingRemover staleDeadLetterBindingRemover(
            org.springframework.amqp.core.AmqpAdmin admin,
            @Value("${app.rabbit.queue}") String queueName,
            @Value("${app.events.dead-letter-exchange}") String dlx) {
        return new StaleDeadLetterBindingRemover(admin, queueName + ".dlq", dlx);
    }
}

/** Appends every audited event; the correlation id of the event is in every log line it causes. */
@Component
class AuditListener {

    private static final Logger log = LoggerFactory.getLogger(AuditListener.class);

    private final AuditLogService service;
    private final ObjectMapper mapper;

    AuditListener(AuditLogService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @RabbitListener(queues = "${app.rabbit.queue}")
    public void receive(byte[] body) throws Exception {
        JsonNode event = mapper.readTree(body);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId",
                event.path("correlationId").asText(null))) {
            log.info("Auditing {} event {}", event.path("eventType").asText(), event.path("eventId").asText());
            service.append(event);
        }
    }
}
