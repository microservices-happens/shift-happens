package dk.ek.shift_happens.cooperation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.ConnectionFactory;
import dk.ek.shift_happens.cooperation.support.ServiceContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SchedulingAiIT {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void cancellingAnAssignmentProducesAnAiSuggestionThroughRabbitMq() throws Exception {
        ServiceContainer.requireDockerOrSkip();
        var config = Files.createTempFile("ollama-stub-", ".py");
        Files.writeString(config, """
                from http.server import BaseHTTPRequestHandler, HTTPServer
                import json

                class Model(BaseHTTPRequestHandler):
                    def do_GET(self):
                        self.send_response(200)
                        self.end_headers()

                    def do_POST(self):
                        self.rfile.read(int(self.headers.get('Content-Length', 0)))
                        answer = {'reason': 'Eligible replacement', 'rankedCandidates': [
                            {'employeeId': 602, 'rank': 1}, {'employeeId': 601, 'rank': 2}]}
                        body = json.dumps({'response': json.dumps(answer)}).encode()
                        self.send_response(200)
                        self.send_header('Content-Type', 'application/json')
                        self.send_header('Content-Length', str(len(body)))
                        self.end_headers()
                        self.wfile.write(body)

                HTTPServer(('0.0.0.0', 80), Model).serve_forever()
                """);
        try (Network network = Network.newNetwork();
             var rabbit = new RabbitMQContainer("rabbitmq:4-management")
                     .withNetwork(network).withNetworkAliases("rabbitmq")
                     .withAdminUser("shift").withAdminPassword("shift_dev");
             var schedulingDb = new PostgreSQLContainer<>("postgres:17-alpine")
                     .withNetwork(network).withNetworkAliases("scheduling-db");
             var aiDb = new PostgreSQLContainer<>("postgres:17-alpine")
                     .withNetwork(network).withNetworkAliases("ai-db");
             var model = new GenericContainer<>("python:3.12-alpine").withNetwork(network).withNetworkAliases("model")
                     .withCopyFileToContainer(MountableFile.forHostPath(config), "/model.py").withCommand("python", "/model.py")
                     .withExposedPorts(80).waitingFor(Wait.forHttp("/api/generate"));
             var scheduling = ServiceContainer.create("scheduling-service", network);
             var ai = ServiceContainer.create("ai-service", network)) {
            rabbit.start();
            schedulingDb.start();
            aiDb.start();
            model.start();
            configure(scheduling, schedulingDb, "scheduling-db");
            configure(ai, aiDb, "ai-db");
            scheduling.withEnv("APP_ROLE", "command");
            ai.withEnv("LLM_BASE_URL", "http://model");
            scheduling.start();
            ai.start();

            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(rabbit.getHost());
            factory.setPort(rabbit.getAmqpPort());
            factory.setUsername("shift");
            factory.setPassword("shift_dev");
            try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
                String queue = channel.queueDeclare().getQueue();
                channel.queueBind(queue, "shift-happens.events", "ai.replacement.suggested.v1");
                for (long id : new long[]{601, 602}) {
                    var event = Map.of("eventId", UUID.randomUUID().toString(),
                            "eventType", "workforce.employee.created.v1", "occurredAt", Instant.now().toString(),
                            "producer", "workforce-service", "aggregateId", id, "aggregateVersion", 1,
                            "correlationId", "scheduling-ai-test",
                            "data", Map.of("employeeId", id, "firstName", "Candidate", "lastName", Long.toString(id),
                                    "employmentStatus", "ACTIVE", "jobRoleIds", new long[]{7}, "weeklyContractHours", 37));
                    channel.basicPublish("shift-happens.events", "workforce.employee.created.v1", null,
                            mapper.writeValueAsBytes(event));
                }
                await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
                    try (var db = DriverManager.getConnection(schedulingDb.getJdbcUrl(),
                            schedulingDb.getUsername(), schedulingDb.getPassword());
                         var statement = db.createStatement();
                         var result = statement.executeQuery("select count(*) from employee_replica")) {
                        result.next();
                        assertThat(result.getInt(1)).isEqualTo(2);
                    }
                });
                Instant start = Instant.now().plusSeconds(5 * 86400);
                JsonNode shift = request(scheduling, "POST", "/v1/shifts", """
                        {"departmentId":2,"workLocationId":3,"shiftName":"Cooperation",
                         "startDatetime":"%s","endDatetime":"%s","shiftStatus":"PLANNED",
                         "requiredJobRoles":[{"jobRoleId":7,"requiredEmployeeCount":1}]}
                        """.formatted(start, start.plusSeconds(28800)));
                long shiftId = shift.path("shiftId").asLong();
                assertThat(shiftId).isPositive();
                JsonNode assignment = request(scheduling, "POST", "/v1/shiftassignments", """
                        {"shiftId":%d,"employeeId":601,"assignmentStatus":"ASSIGNED"}
                        """.formatted(shiftId));
                request(scheduling, "PUT", "/v1/shiftassignments/" + assignment.path("shiftAssignmentId").asLong(),
                        """
                        {"shiftId":%d,"employeeId":601,"assignmentStatus":"CANCELLED"}
                        """.formatted(shiftId));

                var received = new AtomicReference<byte[]>();
                await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                    var message = channel.basicGet(queue, true);
                    assertThat(message).isNotNull();
                    received.set(message.getBody());
                });
                JsonNode output = mapper.readTree(received.get());
                assertThat(output.path("data").path("shiftId").asLong()).isEqualTo(shiftId);
                assertThat(output.path("data").path("source").asText()).isEqualTo("llm");
                assertThat(output.path("data").path("rankedCandidates")).hasSize(2);
                assertThat(output.path("data").path("rankedCandidates").get(0).path("employeeId").asLong())
                        .isEqualTo(602);
            }
        } finally {
            Files.deleteIfExists(config);
        }
    }

    private static void configure(GenericContainer<?> service, PostgreSQLContainer<?> db, String host) {
        service.withEnv(Map.of("DB_HOST", host, "DB_NAME", db.getDatabaseName(), "DB_USER", db.getUsername(),
                "DB_PASSWORD", db.getPassword(), "RABBITMQ_HOST", "rabbitmq", "RABBITMQ_USER", "shift",
                "RABBITMQ_PASSWORD", "shift_dev", "RABBITMQ_VHOST", "/"));
    }

    private JsonNode request(GenericContainer<?> service, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://" + service.getHost() + ":"
                        + service.getMappedPort(8080) + path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body())
                    .isBetween(200, 299);
            return mapper.readTree(response.body());
        }
    }
}
