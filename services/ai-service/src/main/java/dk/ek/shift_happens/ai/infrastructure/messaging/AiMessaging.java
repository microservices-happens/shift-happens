package dk.ek.shift_happens.ai.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.ai.application.SuggestionService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Configuration
class AiTopology {
    @Bean TopicExchange aiEvents(@Value("${app.events.exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }
    @Bean TopicExchange aiDeadLetters(@Value("${app.events.dead-letter-exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }
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
    @Bean Binding aiDeadLetterBinding(Queue aiDeadLetterQueue, TopicExchange aiDeadLetters) {
        return BindingBuilder.bind(aiDeadLetterQueue).to(aiDeadLetters).with("#");
    }
    @Bean SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}

@Component
class AiListener {
    private final SuggestionService service;
    private final ObjectMapper mapper;
    AiListener(SuggestionService service, ObjectMapper mapper) { this.service = service; this.mapper = mapper; }
    @RabbitListener(queues = "${app.rabbit.queue}")
    public void receive(byte[] body) throws Exception { service.handle(mapper.readTree(body)); }
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
