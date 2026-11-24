package dk.ek.shift_happens.ai.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.ai.application.ModelCallMemory;
import dk.ek.shift_happens.ai.application.SuggestionService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetrySynchronizationManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Consumer topology (contracts/README.md): one durable queue bound to the exact key, three attempts (1 s, then
 * 2 s apart), then the message is republished to the dead-letter exchange with the queue name as routing key and
 * lands in {@code <queue>.dlq}, which is bound with exactly that key. No infinite requeue.
 *
 * <p>The queue arguments are deliberately unchanged (only {@code x-dead-letter-exchange}, no
 * {@code x-dead-letter-routing-key}): RabbitMQ refuses to redeclare an existing queue with different arguments.
 * {@link StaleDeadLetterBindingRemover} deletes the {@code #} DLQ binding older versions left behind.
 */
@Configuration
class AiTopology {
    @Bean TopicExchange aiEvents(@Value("${app.events.exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }
    @Bean TopicExchange aiDeadLetters(@Value("${app.events.dead-letter-exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }
    /** Arguments unchanged since the queue was first declared: changing them breaks existing brokers. */
    @Bean Queue aiQueue(@Value("${app.rabbit.queue}") String name,
                        @Value("${app.events.dead-letter-exchange}") String dlx) {
        return QueueBuilder.durable(name).deadLetterExchange(dlx).build();
    }
    @Bean Binding aiBinding(Queue aiQueue, TopicExchange aiEvents) {
        return BindingBuilder.bind(aiQueue).to(aiEvents).with("scheduling.shift.understaffed.v1");
    }
    @Bean Queue aiDeadLetterQueue(@Value("${app.rabbit.queue}") String name) {
        return QueueBuilder.durable(name + ".dlq").build();
    }
    @Bean Binding aiDeadLetterBinding(Queue aiDeadLetterQueue, TopicExchange aiDeadLetters,
                                      @Value("${app.rabbit.queue}") String name) {
        return BindingBuilder.bind(aiDeadLetterQueue).to(aiDeadLetters).with(name);
    }
    /** Injecting AmqpAdmin first makes its declaring listener run before this one on every connection. */
    @Bean StaleDeadLetterBindingRemover aiStaleDeadLetterBindingRemover(ConnectionFactory connectionFactory,
            AmqpAdmin admin, Queue aiDeadLetterQueue, TopicExchange aiDeadLetters) {
        StaleDeadLetterBindingRemover remover = new StaleDeadLetterBindingRemover(admin,
                aiDeadLetterQueue.getName(), aiDeadLetters.getName());
        connectionFactory.addConnectionListener(remover);
        return remover;
    }
    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate,
            @Value("${app.events.dead-letter-exchange}") String dlx,
            @Value("${app.rabbit.queue}") String queueName) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                // 1 s, then 2 s. The model is called at most once per message (ModelCallMemory), so a message
                // takes at most LLM_TIMEOUT + 3 s of back-off + three short database attempts.
                .backOffOptions(1000, 2.0, 10000)
                // Without an explicit key spring-amqp would republish with "error." + the original key.
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, dlx, queueName))
                .build());
        return factory;
    }
}

@Component
class AiListener {
    private static final Logger log = LoggerFactory.getLogger(AiListener.class);
    /** RetryContext attribute that carries the model outcome from one attempt of a message to the next. */
    static final String MODEL_CALL = "ai.model-call";

    private final SuggestionService service;
    private final ObjectMapper mapper;
    AiListener(SuggestionService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    @RabbitListener(queues = "${app.rabbit.queue}")
    public void receive(byte[] body) throws Exception {
        JsonNode event = mapper.readTree(body);
        // P1.6: the event's correlation id is in every log line written while it is handled.
        String correlationId = event.path("correlationId").asText(null);
        if (correlationId != null) {
            Span.current().setAttribute("app.correlation_id", correlationId);
        }
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", correlationId)) {
            log.info("Received {} event {}", event.path("eventType").asText(), event.path("eventId").asText());
            service.handle(event, memoryOfThisDelivery());
        }
    }

    /** The retry advice opens one RetryContext per delivery and keeps it across the three attempts. */
    private static ModelCallMemory memoryOfThisDelivery() {
        RetryContext context = RetrySynchronizationManager.getContext();
        if (context == null) return ModelCallMemory.none();
        return new ModelCallMemory() {
            @Override public ModelCall recall() { return (ModelCall) context.getAttribute(MODEL_CALL); }
            @Override public void remember(ModelCall call) { context.setAttribute(MODEL_CALL, call); }
        };
    }
}

@Component
class AiOutboxRelay {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final String exchange;
    AiOutboxRelay(JdbcTemplate jdbc, RabbitTemplate rabbit, @Value("${app.events.exchange}") String exchange) {
        this.jdbc = jdbc; this.rabbit = rabbit; this.exchange = exchange;
    }
    @Scheduled(fixedDelayString = "${OUTBOX_RELAY_DELAY:2000}")
    public void publish() {
        jdbc.query("SELECT event_id,event_type,payload FROM outbox_event WHERE published_at IS NULL ORDER BY created_at LIMIT 50",
                rs -> {
                    UUID id = rs.getObject("event_id", UUID.class);
                    rabbit.send(exchange, rs.getString("event_type"), MessageBuilder
                            .withBody(rs.getString("payload").getBytes(StandardCharsets.UTF_8))
                            .setContentType(MessageProperties.CONTENT_TYPE_JSON).setMessageId(id.toString()).build());
                    jdbc.update("UPDATE outbox_event SET published_at=? WHERE event_id=?",
                            OffsetDateTime.now(ZoneOffset.UTC), id);
                });
    }
}
