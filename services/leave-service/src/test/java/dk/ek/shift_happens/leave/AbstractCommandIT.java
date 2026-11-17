package dk.ek.shift_happens.leave;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared harness of the command-role integration tests: real PostgreSQL and RabbitMQ (no H2, no
 * embedded broker), one Spring context.
 *
 * <p>The containers start once in a static initializer so every subclass reuses the same cached
 * Spring context and the IT run pays for two containers only. Assertions in subclasses must be
 * scoped to the rows they created, because the tests share a database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.outbox.relay-delay=200", "spring.datasource.hikari.connection-timeout=2000"})
abstract class AbstractCommandIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

    static {
        POSTGRES.start();
        RABBIT.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", () -> "shift");
        registry.add("spring.rabbitmq.password", () -> "shift_dev");
        registry.add("spring.rabbitmq.virtual-host", () -> "/");
    }

    @Autowired
    protected RabbitAdmin rabbitAdmin;
    @Autowired
    protected RabbitTemplate rabbitTemplate;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected TopicExchange eventsExchange;

    /** Declares a throwaway queue bound to the events exchange for one routing key. */
    protected String bindTestQueue(String queueName, String routingKey) {
        // Durable and non-exclusive: RabbitMQ 4 rejects transient non-exclusive queues.
        Queue queue = new Queue(queueName, true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with(routingKey));
        return queueName;
    }
}
