package dk.ek.shift_happens.notification.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.notification.application.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Consumer topology (contracts/README.md): one durable queue bound to the exact routing keys of the
 * notification.events queue, three attempts (1 s, then 2 s apart), then the message is republished to the
 * dead-letter exchange with the queue name as routing key and lands in {@code <queue>.dlq}. No infinite
 * requeue.
 */
@Configuration
class NotificationTopology {

    @Bean
    TopicExchange notificationEvents(@Value("${app.events.exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }

    @Bean
    TopicExchange notificationDeadLetters(@Value("${app.events.dead-letter-exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }

    /** Arguments unchanged since the queue was first declared: changing them breaks existing brokers. */
    @Bean
    Queue notificationQueue(@Value("${app.rabbit.queue}") String name,
                            @Value("${app.events.dead-letter-exchange}") String dlx) {
        return QueueBuilder.durable(name).deadLetterExchange(dlx).build();
    }

    @Bean
    Queue notificationDeadLetterQueue(@Value("${app.rabbit.queue}") String name) {
        return QueueBuilder.durable(name + ".dlq").build();
    }

    @Bean
    org.springframework.amqp.core.Declarables notificationBindings(Queue notificationQueue,
                                                                   TopicExchange notificationEvents) {
        return new org.springframework.amqp.core.Declarables(NotificationService.HANDLED_EVENTS.stream()
                .map(key -> (org.springframework.amqp.core.Declarable)
                        BindingBuilder.bind(notificationQueue).to(notificationEvents).with(key))
                .toList());
    }

    @Bean
    Binding notificationDeadLetterBinding(Queue notificationDeadLetterQueue, TopicExchange notificationDeadLetters,
                                          @Value("${app.rabbit.queue}") String queueName) {
        return BindingBuilder.bind(notificationDeadLetterQueue).to(notificationDeadLetters).with(queueName);
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
        // Registers the remover that deletes the stale "#" DLQ binding older deployments left behind.
        connectionFactory.addConnectionListener(staleBindingRemover);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 2.0, 10000)
                // Without an explicit key spring-amqp would republish with "error." + the original key.
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

/** Consumes notification.events; the correlation id of the event is in every log line it causes. */
@Component
class NotificationListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    private final NotificationService service;
    private final ObjectMapper mapper;

    NotificationListener(NotificationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @RabbitListener(queues = "${app.rabbit.queue}")
    public void receive(byte[] body) throws Exception {
        JsonNode event = mapper.readTree(body);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId",
                event.path("correlationId").asText(null))) {
            log.info("Received {} event {}", event.path("eventType").asText(), event.path("eventId").asText());
            service.handle(event);
        }
    }
}

/** Publishes the email requests the consumers committed to the outbox (P1.8). */
@Component
class NotificationOutboxRelay {

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final String exchange;

    NotificationOutboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, @Value("${app.events.exchange}") String exchange) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
        this.exchange = exchange;
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay-delay}")
    public void publish() {
        jdbc.query("SELECT event_id,event_type,payload FROM outbox_event WHERE published_at IS NULL "
                + "ORDER BY created_at LIMIT 50", rs -> {
            UUID id = rs.getObject("event_id", UUID.class);
            rabbit.send(exchange, rs.getString("event_type"), MessageBuilder
                    .withBody(rs.getString("payload").getBytes(StandardCharsets.UTF_8))
                    .setContentType(MessageProperties.CONTENT_TYPE_JSON).setMessageId(id.toString()).build());
            jdbc.update("UPDATE outbox_event SET published_at=? WHERE event_id=?",
                    OffsetDateTime.now(ZoneOffset.UTC), id);
        });
    }
}
