package dk.ek.shift_happens.emailfunction.infrastructure.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.ek.shift_happens.emailfunction.application.EmailService;
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
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Queue email.send: one durable queue bound to notification.email.requested.v1, three attempts (1 s, then
 * 2 s apart), then the dead-letter exchange with the queue name as routing key and {@code <queue>.dlq}.
 * The message is acked only after SMTP accepted it.
 */
@Configuration
class MailTopology {

    @Bean
    TopicExchange mailEvents(@Value("${app.events.exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }

    @Bean
    TopicExchange mailDeadLetters(@Value("${app.events.dead-letter-exchange}") String name) {
        return ExchangeBuilder.topicExchange(name).durable(true).build();
    }

    @Bean
    Queue mailQueue(@Value("${app.rabbit.queue}") String name,
                    @Value("${app.events.dead-letter-exchange}") String dlx) {
        return QueueBuilder.durable(name).deadLetterExchange(dlx).build();
    }

    @Bean
    Queue mailDeadLetterQueue(@Value("${app.rabbit.queue}") String name) {
        return QueueBuilder.durable(name + ".dlq").build();
    }

    @Bean
    Binding mailBinding(Queue mailQueue, TopicExchange mailEvents) {
        return BindingBuilder.bind(mailQueue).to(mailEvents).with("notification.email.requested.v1");
    }

    @Bean
    Binding mailDeadLetterBinding(Queue mailDeadLetterQueue, TopicExchange mailDeadLetters,
                                  @Value("${app.rabbit.queue}") String queueName) {
        return BindingBuilder.bind(mailDeadLetterQueue).to(mailDeadLetters).with(queueName);
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            RabbitTemplate rabbitTemplate,
            @Value("${app.events.dead-letter-exchange}") String dlx,
            @Value("${app.rabbit.queue}") String queueName) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(1000, 2.0, 10000)
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, dlx, queueName))
                .build());
        return factory;
    }
}

/** One email per message; the correlation id of the event is in every log line it causes. */
@Component
class MailListener {

    private static final Logger log = LoggerFactory.getLogger(MailListener.class);

    private final EmailService emails;
    private final ObjectMapper mapper;

    MailListener(EmailService emails, ObjectMapper mapper) {
        this.emails = emails;
        this.mapper = mapper;
    }

    @RabbitListener(queues = "${app.rabbit.queue}")
    public void receive(byte[] body) throws Exception {
        JsonNode event = mapper.readTree(body);
        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId",
                event.path("correlationId").asText(null))) {
            log.info("Sending email for event {}", event.path("eventId").asText());
            emails.handle(event);
        }
    }
}

/**
 * The serverless half of the function: with {@code EMAIL_FUNCTION_EXIT_WHEN_IDLE=true} the process closes
 * once it has sent at least one email and the queue is empty, so the Kubernetes Job completes and KEDA can
 * start a fresh one for the next burst. Compose leaves the flag false and keeps a long-running worker.
 */
@Component
class IdleExit {

    private static final Logger log = LoggerFactory.getLogger(IdleExit.class);

    private final EmailService emails;
    private final AmqpAdmin admin;
    private final org.springframework.context.ConfigurableApplicationContext context;
    private final boolean exitWhenIdle;
    private final String queue;
    private final java.util.concurrent.atomic.AtomicInteger emptyPolls = new java.util.concurrent.atomic.AtomicInteger();

    IdleExit(EmailService emails, AmqpAdmin admin,
             @Lazy org.springframework.context.ConfigurableApplicationContext context,
             @Value("${app.function.exit-when-idle}") boolean exitWhenIdle,
             @Value("${app.rabbit.queue}") String queue) {
        this.emails = emails;
        this.admin = admin;
        this.context = context;
        this.exitWhenIdle = exitWhenIdle;
        this.queue = queue;
    }

    @Scheduled(fixedDelayString = "${app.function.idle-poll}")
    public void exitWhenDrained() {
        if (!exitWhenIdle) {
            return;
        }
        var info = ((RabbitAdmin) admin).getQueueInfo(queue);
        if (info == null || info.getMessageCount() > 0) {
            emptyPolls.set(0);
            return;
        }
        // A ScaledJob can start after another job drained the burst; it must still finish. Three empty
        // polls give the listener time to receive the message that triggered the scale-out.
        int empty = emptyPolls.incrementAndGet();
        if (emails.handledSomething() || empty >= 3) {
            log.info("Queue {} is empty; the job is done", queue);
            // A ScaledJob must terminate for KEDA to count it as complete. halt() bypasses shutdown
            // hooks: a graceful listener close can block indefinitely on a drained queue.
            Runtime.getRuntime().halt(0);
        }
    }
}
