package dk.ek.shift_happens.scheduling.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionListener;

/**
 * Removes the {@code #} binding that earlier versions declared between the dead-letter exchange and
 * {@code <queue>.dlq} (both roles). With it, the DLQ received a copy of every dead letter of every service.
 *
 * <p>Bindings are durable and RabbitAdmin only ever adds them, so the old binding would stay on an existing
 * broker forever. This runs on every new broker connection, after RabbitAdmin has declared the topology (it
 * is registered after RabbitAdmin's own listener). Unbinding a binding that does not exist is a no-op in
 * RabbitMQ, so on a fresh broker nothing happens.
 */
public final class StaleDeadLetterBindingRemover implements ConnectionListener {

    static final String STALE_ROUTING_KEY = "#";

    private static final Logger log = LoggerFactory.getLogger(StaleDeadLetterBindingRemover.class);

    private final AmqpAdmin admin;
    private final Binding staleBinding;

    private StaleDeadLetterBindingRemover(AmqpAdmin admin, String deadLetterQueue, String deadLetterExchange) {
        this.admin = admin;
        this.staleBinding = new Binding(deadLetterQueue, Binding.DestinationType.QUEUE, deadLetterExchange,
                STALE_ROUTING_KEY, null);
    }

    /**
     * Registers the remover. Pass the AmqpAdmin bean so it is initialised first: its declaring listener is then
     * registered before this one and runs first on every connection.
     */
    public static StaleDeadLetterBindingRemover register(ConnectionFactory connectionFactory, AmqpAdmin admin,
                                                         String deadLetterQueue, String deadLetterExchange) {
        StaleDeadLetterBindingRemover remover =
                new StaleDeadLetterBindingRemover(admin, deadLetterQueue, deadLetterExchange);
        connectionFactory.addConnectionListener(remover);
        return remover;
    }

    @Override
    public void onCreate(Connection connection) {
        try {
            admin.removeBinding(staleBinding);
            log.debug("Ensured {} is not bound to {} with '{}'", staleBinding.getDestination(),
                    staleBinding.getExchange(), STALE_ROUTING_KEY);
        } catch (AmqpException e) {
            log.warn("Could not remove the stale '{}' binding of {} to {}; it still receives other queues' "
                            + "dead letters (see docs/wiki/Troubleshooting.md): {}", STALE_ROUTING_KEY,
                    staleBinding.getDestination(), staleBinding.getExchange(), e.toString());
        }
    }
}
