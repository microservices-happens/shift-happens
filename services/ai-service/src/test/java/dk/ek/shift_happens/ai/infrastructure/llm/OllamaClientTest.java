package dk.ek.shift_happens.ai.infrastructure.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OllamaClientTest {

    @Test
    void timesOutWhenTheModelDoesNotSendResponseHeaders() throws Exception {
        var received = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        HttpServer model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/api/generate", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                received.countDown();
                release.await(10, TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, -1);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        model.start();
        try {
            var client = new OllamaClient(new ObjectMapper(),
                    "http://127.0.0.1:" + model.getAddress().getPort(), "test-model", Duration.ofSeconds(2));
            assertThatThrownBy(() -> client.generate("Rank candidates", List.of(7L)))
                    .isInstanceOf(HttpTimeoutException.class);
            assertThat(received.getCount()).isZero();
        } finally {
            release.countDown();
            model.stop(0);
        }
    }
}
