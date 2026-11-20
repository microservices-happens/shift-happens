package dk.ek.shift_happens.scheduling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Helpers of the dead-letter ITs: seeds a broker with the topology of the version before the DLQ fix, reads
 * bindings from the management API and drains queues.
 */
final class LegacyBroker {

    static final String EVENTS = "shift-happens.events";
    static final String DLX = "shift-happens.dlx";
    /** A queue of another service whose dead letters are routed by queue name, like leave-service does. */
    static final String OTHER_DLQ = "it.other.dlq";

    private LegacyBroker() {
    }

    /**
     * Declares what the old topology declared for {@code queue} (only {@code x-dead-letter-exchange}, DLQ bound
     * with {@code #}), a foreign DLQ bound by queue name and by {@code foreignKey}, and publishes
     * {@code waitingEvent} with routing key {@code waitingKey} so it is still in the queue when the service starts.
     */
    static void seed(RabbitMQContainer rabbit, String queue, List<String> queueKeys, String foreignKey,
                     String waitingKey, String waitingEvent) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbit.getHost());
        factory.setPort(rabbit.getAmqpPort());
        factory.setUsername("shift");
        factory.setPassword("shift_dev");
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            channel.exchangeDeclare(EVENTS, "topic", true);
            channel.exchangeDeclare(DLX, "topic", true);
            channel.queueDeclare(queue, true, false, false, Map.of("x-dead-letter-exchange", DLX));
            for (String key : queueKeys) {
                channel.queueBind(queue, EVENTS, key);
            }
            channel.queueDeclare(queue + ".dlq", true, false, false, null);
            channel.queueBind(queue + ".dlq", DLX, "#");
            channel.queueDeclare(OTHER_DLQ, true, false, false, null);
            channel.queueBind(OTHER_DLQ, DLX, "it.other");
            channel.queueBind(OTHER_DLQ, DLX, foreignKey);
            channel.basicPublish(EVENTS, waitingKey, new AMQP.BasicProperties.Builder()
                            .contentType("application/json").deliveryMode(2).build(),
                    waitingEvent.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("could not seed the legacy topology", e);
        }
    }

    /** Routing keys of every binding from the DLX to the queue, read from the management API. */
    static List<String> dlxBindingKeys(RabbitMQContainer rabbit, String queue) throws Exception {
        URI uri = URI.create(rabbit.getHttpUrl() + "/api/bindings/%2F/e/" + DLX + "/q/" + queue);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString("shift:shift_dev".getBytes(StandardCharsets.UTF_8)))
                .GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        List<String> keys = new ArrayList<>();
        for (JsonNode binding : new ObjectMapper().readTree(response.body())) {
            keys.add(binding.path("routing_key").asText());
        }
        return keys;
    }

    static Message awaitMessageContaining(RabbitTemplate rabbitTemplate, String queue, String marker) {
        List<Message> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            Message message;
            while ((message = rabbitTemplate.receive(queue, 300)) != null) {
                seen.add(message);
            }
            return seen.stream().anyMatch(m -> body(m).contains(marker));
        });
        return seen.stream().filter(m -> body(m).contains(marker)).findFirst().orElseThrow();
    }

    /** Collects the DLQ until {@code sentinel} (published last) arrives and returns every body seen. */
    static List<String> drainUntil(RabbitTemplate rabbitTemplate, String queue, String sentinel) {
        List<String> arrived = new ArrayList<>();
        await().atMost(Duration.ofSeconds(20)).until(() -> {
            arrived.addAll(drain(rabbitTemplate, queue));
            return arrived.stream().anyMatch(body -> body.contains(sentinel));
        });
        return arrived;
    }

    static List<String> drain(RabbitTemplate rabbitTemplate, String queue) {
        List<String> bodies = new ArrayList<>();
        Message message;
        while ((message = rabbitTemplate.receive(queue, 300)) != null) {
            bodies.add(body(message));
        }
        return bodies;
    }

    static String body(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    static int count(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            count++;
        }
        return count;
    }
}
