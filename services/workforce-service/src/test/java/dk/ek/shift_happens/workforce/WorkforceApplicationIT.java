package dk.ek.shift_happens.workforce;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import dk.ek.shift_happens.workforce.application.EmployeeTermsRefreshJob;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dk.ek.shift_happens.workforce.api.dto.CreateEmployeeRequest;
import dk.ek.shift_happens.workforce.api.dto.EmployeeResponse;
import dk.ek.shift_happens.workforce.infrastructure.messaging.EventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration test of the Workforce slice: REST → PostgreSQL → outbox → RabbitMQ, against the real
 * engines (PostgreSQL and RabbitMQ in Testcontainers, no mocks).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkforceApplicationIT {

    private static final String TEST_QUEUE = "it.workforce.employee.events";
    private static final Path PAYLOAD_SCHEMAS = Path.of("../../contracts/events/payloads");
    private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management"))
            .withAdminUser("shift")
            .withAdminPassword("shift_dev");

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

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate http;
    @Autowired
    private RabbitAdmin rabbitAdmin;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EmployeeTermsRefreshJob termsRefreshJob;
    @Autowired
    private TopicExchange eventsExchange;

    @BeforeEach
    void bindATestQueue() {
        // Durable and non-exclusive: RabbitMQ 4 rejects transient non-exclusive queues.
        Queue queue = new Queue(TEST_QUEUE, true, false, false);
        rabbitAdmin.declareQueue(queue);
        rabbitAdmin.declareBinding(BindingBuilder.bind(queue).to(eventsExchange).with("workforce.employee.#"));
        rabbitTemplate.setReceiveTimeout(500);
        while (rabbitTemplate.receive(TEST_QUEUE) != null) {
            // drain anything left by an earlier test
        }
    }

    @Test
    void healthEndpointIsPublicAndUp() {
        assertThat(http.getForEntity("/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void creatingAnEmployeePublishesTheEventAndMarksTheOutboxRow() throws IOException {
        CreateEmployeeRequest request = new CreateEmployeeRequest("EMP-1001", "Employee", "Ada", "Lovelace",
                "ada@shift.dk", "+45 50123456", LocalDate.of(2024, 1, 15), "ACTIVE", null);

        var response = createEmployee(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().employeeId()).isNotNull();

        Message message = receiveEventFor("ada@shift.dk");
        assertPayloadMatches(message, "employee.schema.json");
        assertThat(message.getMessageProperties().getReceivedRoutingKey())
                .isEqualTo("workforce.employee.created.v1");

        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        assertThat(body)
                .contains("\"eventType\":\"workforce.employee.created.v1\"")
                .contains("\"producer\":\"workforce-service\"")
                .contains("\"aggregateId\":" + response.getBody().employeeId())
                .contains("\"email\":\"ada@shift.dk\"")
                .contains("\"jobRoleIds\":[]")
                .doesNotContain("salary")
                .doesNotContain("password");

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(unpublishedOutboxRowsFor("ada@shift.dk")).isZero());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employee WHERE email = ?",
                Integer.class, "ada@shift.dk")).isEqualTo(1);
    }

    @Test
    void anEmployeeCanBeReadBack() {
        CreateEmployeeRequest request = new CreateEmployeeRequest("EMP-1002", "Manager", "Grace", "Hopper",
                "grace@shift.dk", null, null, "ACTIVE", null);
        EmployeeResponse created = createEmployee(request).getBody();

        var response = http.getForEntity("/v1/employees/" + created.employeeId(), EmployeeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().email()).isEqualTo("grace@shift.dk");
        assertThat(response.getBody().userRole()).isEqualTo("Manager");
    }

    @Test
    void aDuplicateEmployeeNumberIsAConflict() {
        CreateEmployeeRequest request = new CreateEmployeeRequest("EMP-1003", "Employee", "Alan", "Turing",
                "alan@shift.dk", null, null, "ACTIVE", null);
        createEmployee(request);

        var duplicate = createEmployee(request);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicate.getHeaders().getContentType().toString()).contains("application/problem+json");
    }

    @Test
    void anUnknownEmployeeIsNotFound() {
        assertThat(http.getForEntity("/v1/employees/999999", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void theEnvelopeStillHasTheFieldsFromTheContract() {
        assertThat(EventEnvelope.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("eventId", "eventType", "occurredAt", "producer", "aggregateId",
                        "aggregateVersion", "correlationId", "causationId", "data");
    }

    @Test
    void employeesAreListed() {
        createEmployee(new CreateEmployeeRequest("EMP-1007", "Employee", "Dorothy", "Vaughan",
                "dorothy@shift.dk", null, null, "ACTIVE", null));

        ResponseEntity<EmployeeResponse[]> response = http.getForEntity("/v1/employees", EmployeeResponse[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).extracting(EmployeeResponse::email).contains("dorothy@shift.dk");
    }

    @Test
    void employeeCanBeUpdatedFilteredAndSoftDeleted() throws Exception {
        EmployeeResponse created = createEmployee(new CreateEmployeeRequest("EMP-1010", "Employee", "Klara",
                "Jensen", "klara@shift.dk", null, null, "ACTIVE", 71L)).getBody();
        String path = "/v1/employees/" + created.employeeId();
        String filters = "?employmentStatus=INACTIVE&primaryWorkLocationId=72&userRole=Manager"
                + "&email=UPDATED&firstName=LAR&lastName=JENS";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Correlation-Id", "employee-update-it")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("""
                        {"employeeNumber":"EMP-1010","userRole":"Manager","firstName":"Klara",
                         "lastName":"Jensen","email":"updated@shift.dk","employmentStatus":"INACTIVE",
                         "primaryWorkLocationId":72,"salary":12345}
                        """)).build();
        try (var client = HttpClient.newHttpClient()) {
            var updated = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(updated.statusCode()).isEqualTo(200);
            assertThat(updated.body()).contains("updated@shift.dk").doesNotContain("salary");
            assertThat(http.getForObject(path, EmployeeResponse.class).email()).isEqualTo("updated@shift.dk");
            assertThat(http.getForObject("/v1/employees" + filters, EmployeeResponse[].class))
                    .extracting(EmployeeResponse::employeeId).containsExactly(created.employeeId());
            assertThat(http.getForObject("/v1/employees" + filters.replace("Id=72", "Id=73"),
                    EmployeeResponse[].class)).isEmpty();

            Message updatedEvent = receiveEventFor("updated@shift.dk");
            assertPayloadMatches(updatedEvent, "employee.schema.json");
            assertThat(updatedEvent.getMessageProperties().getReceivedRoutingKey())
                    .isEqualTo("workforce.employee.updated.v1");
            assertThat(new String(updatedEvent.getBody(), StandardCharsets.UTF_8))
                    .contains("\"aggregateVersion\":2", "employee-update-it").doesNotContain("salary");

            assertThat(http.exchange(path, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(http.getForEntity(path, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(http.getForObject("/v1/employees" + filters, EmployeeResponse[].class)).isEmpty();
            assertThat(client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(404);
            assertThat(jdbc.queryForObject("select deleted from employee where employee_id = ?",
                    Boolean.class, created.employeeId())).isTrue();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                Message deletedEvent = rabbitTemplate.receive(TEST_QUEUE);
                assertThat(deletedEvent).isNotNull();
                assertThat(deletedEvent.getMessageProperties().getReceivedRoutingKey())
                        .isEqualTo("workforce.employee.deleted.v1");
                assertPayloadMatches(deletedEvent, "tombstone.schema.json");
                assertThat(new String(deletedEvent.getBody(), StandardCharsets.UTF_8))
                        .contains("\"aggregateVersion\":3", "\"entityId\":" + created.employeeId(), "\"deletedAt\"")
                        .doesNotContain("salary");
            });
        }
    }

    @Test
    void departmentCrud() throws Exception {
        verifyReferenceDataCrud("departments", "departmentId",
                """
                {"departmentName":"Operations","isActive":true}
                """, """
                {"departmentName":"Support","isActive":false}
                """, "PATCH", "departmentName", "Support");
    }

    @Test
    void jobRoleCrud() throws Exception {
        verifyReferenceDataCrud("jobroles", "jobRoleId",
                """
                {"roleName":"Operator","jobRoleDescription":"Initial","isCertificationRequired":false}
                """, """
                {"roleName":"Supervisor","jobRoleDescription":null,"isCertificationRequired":true}
                """, "PUT", "roleName", "Supervisor");
    }

    @Test
    void aPrimaryWorkLocationCannotBeDeletedWhileReferenced() {
        long locationId = postJson("/v1/worklocations", """
                {"locationName":"Assigned site","city":"Roskilde","country":"DK","timezone":"Europe/Copenhagen"}
                """).getBody().path("workLocationId").asLong();
        String path = "/v1/worklocations/" + locationId;
        createEmployee(new CreateEmployeeRequest("EMP-1038", "Employee", "Nora", "Jensen",
                "nora-location@shift.dk", null, null, "ACTIVE", locationId));

        assertThat(http.exchange(path, HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getForEntity(path, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("update employee set primary_work_location_id = null where employee_number = ?", "EMP-1038");
        assertThat(http.exchange(path, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void workLocationCrud() throws Exception {
        verifyReferenceDataCrud("worklocations", "workLocationId",
                """
                {"locationName":"Office","city":"Copenhagen","country":"DK","timezone":"Europe/Copenhagen"}
                """, """
                {"locationName":"Warehouse","city":"Roskilde","country":"DK","timezone":"Europe/Copenhagen"}
                """, "PUT", "city", "Roskilde");
    }

    private void verifyReferenceDataCrud(String resource, String idField, String createBody,
                                         String updateBody, String updateMethod, String updatedField, String updatedValue)
            throws Exception {
        String collection = "/v1/" + resource;
        HttpHeaders requestHeaders = headers(UUID.randomUUID().toString());
        requestHeaders.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var createRequest = new HttpEntity<>(createBody, requestHeaders);
        HttpHeaders missingKey = new HttpHeaders();
        missingKey.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity(collection, new HttpEntity<>(createBody, missingKey), String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        var created = http.postForEntity(collection, createRequest, JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode initial = created.getBody();
        assertThat(initial.path(idField).asLong()).isPositive();
        assertThat(http.postForEntity(collection, createRequest, JsonNode.class).getBody()).isEqualTo(initial);
        assertThat(http.postForEntity(collection, new HttpEntity<>(updateBody, requestHeaders), String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(http.getForObject(collection, JsonNode[].class)).containsExactly(initial);
        String item = collection + "/" + initial.path(idField).asLong();
        assertThat(http.getForObject(item, JsonNode.class)).isEqualTo(initial);

        var update = HttpRequest.newBuilder(URI.create("http://localhost:" + port + item))
                .header("Content-Type", "application/json")
                .method(updateMethod, HttpRequest.BodyPublishers.ofString(updateBody))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            var updated = client.send(update, HttpResponse.BodyHandlers.ofString());
            assertThat(updated.statusCode()).isEqualTo(200);
            JsonNode updatedBody = objectMapper.readTree(updated.body());
            assertThat(updatedBody.path(updatedField).asText()).isEqualTo(updatedValue);
            assertThat(http.getForObject(item, JsonNode.class)).isEqualTo(updatedBody);
            assertThat(http.exchange(item, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class).getStatusCode())
                    .isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(http.getForEntity(item, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(http.getForObject(collection, JsonNode[].class)).isEmpty();
            assertThat(client.send(update, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
            assertThat(http.exchange(item, HttpMethod.DELETE, HttpEntity.EMPTY, String.class).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void dateBoundariesRefreshTheSnapshotWithoutAnApiCall() throws Exception {
        long employee = createEmployee(new CreateEmployeeRequest("EMP-1041", "Employee", "Ida",
                "Nielsen", "ida-refresh@shift.dk", null, null, "ACTIVE", null)).getBody().employeeId();
        long department = postJson("/v1/departments", """
                {"departmentName":"Refresh department"}
                """).getBody().path("departmentId").asLong();
        long role = postJson("/v1/jobroles", """
                {"roleName":"Refresh role"}
                """).getBody().path("jobRoleId").asLong();
        LocalDate start = LocalDate.now(ZoneOffset.UTC).plusDays(2);
        LocalDate end = start.plusDays(1);

        String contracts = "/v1/employeecontracts";
        var contract = postJson(contracts, """
                {"employeeId":%d,"departmentId":%d,"contractType":"TEMPORARY",
                 "startDate":"%s","endDate":"%s","weeklyHours":37}
                """.formatted(employee, department, start, end));
        assertThat(contract.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String assignments = "/v1/employeejobroles";
        var assignment = postJson(assignments, """
                {"employeeId":%d,"jobRoleId":%d,"assignedDate":"%s","expiryDate":"%s"}
                """.formatted(employee, role, start, end));
        assertThat(assignment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertTermsSnapshot(employee, 3, 0);

        termsRefreshJob.refreshDueTerms(start.minusDays(1));
        termsRefreshJob.refreshDueTerms(start);
        assertTermsSnapshot(employee, 4, 37, role);

        termsRefreshJob.refreshDueTerms(end);
        // Simulate catching up after Workforce was unavailable at the next date boundary.
        termsRefreshJob.refreshDueTerms(end.plusDays(3));
        assertTermsSnapshot(employee, 5, 0);
        termsRefreshJob.refreshDueTerms(end.plusDays(3));
        assertThat(jdbc.queryForObject("""
                select count(*) from outbox_event where aggregate_id = ?
                """, Integer.class, employee)).isEqualTo(5);

        http.delete(assignments + "/" + assignment.getBody().path("employeeJobRoleId").asLong());
        http.delete(contracts + "/" + contract.getBody().path("contractId").asLong());
        http.delete("/v1/jobroles/" + role);
        http.delete("/v1/departments/" + department);
    }

    @Test
    void employeeTermsCrudUpdatesThePublishedSnapshot() throws Exception {
        long employee = createEmployee(new CreateEmployeeRequest("EMP-1039", "Employee", "Emma",
                "Nielsen", "emma-terms@shift.dk", null, null, "ACTIVE", null)).getBody().employeeId();
        long department = postJson("/v1/departments", """
                {"departmentName":"Terms department"}
                """).getBody().path("departmentId").asLong();
        long role = postJson("/v1/jobroles", """
                {"roleName":"Terms role"}
                """).getBody().path("jobRoleId").asLong();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        String contracts = "/v1/employeecontracts";
        String contractBody = """
                {"employeeId":%d,"departmentId":%d,"contractType":"PERMANENT","startDate":"%s","weeklyHours":30}
                """.formatted(employee, department, today.minusDays(1));
        var contractCreated = postJson(contracts, contractBody);
        assertThat(contractCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode contract = contractCreated.getBody();
        String contractPath = contracts + "/" + contract.path("contractId").asLong();
        assertThat(http.getForObject(contracts, JsonNode[].class)).contains(contract);
        assertThat(http.getForObject(contractPath, JsonNode.class)).isEqualTo(contract);

        String assignments = "/v1/employeejobroles";
        String assignmentBody = """
                {"employeeId":%d,"jobRoleId":%d,"assignedDate":"%s"}
                """.formatted(employee, role, today.minusDays(2));
        String expiredBody = assignmentBody.trim().replace("}", ",\"expiryDate\":\"" + today.minusDays(1) + "\"}");
        assertThat(postJson(assignments, expiredBody).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var assigned = postJson(assignments, assignmentBody);
        assertThat(assigned.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode assignment = assigned.getBody();
        String assignmentPath = assignments + "/" + assignment.path("employeeJobRoleId").asLong();
        assertThat(http.getForObject(assignments, JsonNode[].class)).contains(assignment);
        assertThat(http.getForObject(assignmentPath, JsonNode.class)).isEqualTo(assignment);
        assertThat(http.exchange("/v1/jobroles/" + role, HttpMethod.DELETE, HttpEntity.EMPTY, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertTermsSnapshot(employee, 3, 30, role);

        assertThat(http.exchange(contractPath, HttpMethod.PUT, jsonRequest(contractBody.replace(":30", ":34")),
                JsonNode.class).getBody().path("weeklyHours").asInt()).isEqualTo(34);
        String futureAssignment = assignmentBody.replace(today.minusDays(2).toString(), today.plusDays(2).toString());
        assertThat(http.exchange(assignmentPath, HttpMethod.PUT, jsonRequest(futureAssignment),
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertTermsSnapshot(employee, 5, 34);

        assertThat(http.exchange(assignmentPath, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.exchange(contractPath, HttpMethod.DELETE, HttpEntity.EMPTY, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(http.getForEntity(assignmentPath, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity(contractPath, String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertTermsSnapshot(employee, 7, 0);
        http.delete("/v1/jobroles/" + role);
        http.delete("/v1/departments/" + department);
    }

    private void assertTermsSnapshot(long employee, int version, int hours, Long... roles) throws IOException {
        AtomicReference<Message> found = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            Message message = rabbitTemplate.receive(TEST_QUEUE);
            if (message != null) {
                JsonNode event = objectMapper.readTree(message.getBody());
                if (event.path("aggregateId").asLong() == employee
                        && event.path("aggregateVersion").asInt() == version) {
                    found.set(message);
                }
            }
            assertThat(found.get()).as("employee event version %s", version).isNotNull();
        });
        assertThat(found.get().getMessageProperties().getReceivedRoutingKey())
                .isEqualTo("workforce.employee.updated.v1");
        assertPayloadMatches(found.get(), "employee.schema.json");
        JsonNode payload = objectMapper.readTree(found.get().getBody()).path("data");
        assertThat(payload.path("weeklyContractHours").asInt()).isEqualTo(hours);
        assertThat(payload.path("jobRoleIds")).extracting(JsonNode::asLong).containsExactly(roles);
        assertThat(payload.has("salaryAmount")).isFalse();
    }

    private ResponseEntity<JsonNode> postJson(String path, String body) {
        return http.postForEntity(path, jsonRequest(body), JsonNode.class);
    }

    private HttpEntity<String> jsonRequest(String body) {
        HttpHeaders headers = headers(UUID.randomUUID().toString());
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    @Test
    void replayingAnIdempotencyKeyReturnsTheStoredResponseAndHasNoSecondSideEffect() {
        CreateEmployeeRequest request = new CreateEmployeeRequest("EMP-1004", "Employee", "Katherine", "Johnson",
                "katherine@shift.dk", null, LocalDate.of(2023, 3, 1), "ACTIVE", null);
        String idempotencyKey = UUID.randomUUID().toString();

        var first = createEmployee(request, idempotencyKey);
        var replay = createEmployee(request, idempotencyKey);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody()).isNotNull();
        assertThat(replay.getBody().employeeId()).isEqualTo(first.getBody().employeeId());
        assertThat(countEmployeesWithEmail("katherine@shift.dk")).isEqualTo(1);
    }

    @Test
    void theSameIdempotencyKeyWithADifferentBodyIsAConflict() {
        String idempotencyKey = UUID.randomUUID().toString();
        createEmployee(new CreateEmployeeRequest("EMP-1005", "Employee", "Margaret", "Hamilton",
                "margaret@shift.dk", null, null, "ACTIVE", null), idempotencyKey);

        ResponseEntity<String> conflict = postEmployee(new CreateEmployeeRequest("EMP-1006", "Employee", "Barbara",
                "Liskov", "barbara@shift.dk", null, null, "ACTIVE", null), idempotencyKey);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getHeaders().getContentType().toString()).contains("application/problem+json");
        assertThat(countEmployeesWithEmail("barbara@shift.dk")).isZero();
    }

    @Test
    void creatingAnEmployeeWithoutAnIdempotencyKeyIsRejected() {
        CreateEmployeeRequest request = new CreateEmployeeRequest("EMP-1008", "Employee", "Frances", "Allen",
                "frances@shift.dk", null, null, "ACTIVE", null);

        ResponseEntity<String> response = http.postForEntity("/v1/employees", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Idempotency-Key header is required");
        assertThat(countEmployeesWithEmail("frances@shift.dk")).isZero();
    }

    @Test
    void anIdempotencyKeyThatIsNotAUuidIsRejected() {
        CreateEmployeeRequest request = new CreateEmployeeRequest("EMP-1009", "Employee", "Mary", "Keller",
                "mary@shift.dk", null, null, "ACTIVE", null);

        ResponseEntity<String> response = postEmployee(request, "not-a-uuid");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("Idempotency-Key must be a UUID");
        assertThat(countEmployeesWithEmail("mary@shift.dk")).isZero();
    }

    private ResponseEntity<EmployeeResponse> createEmployee(CreateEmployeeRequest request) {
        return createEmployee(request, UUID.randomUUID().toString());
    }

    private ResponseEntity<EmployeeResponse> createEmployee(CreateEmployeeRequest request, String idempotencyKey) {
        return http.exchange("/v1/employees", HttpMethod.POST, new HttpEntity<>(request, headers(idempotencyKey)),
                EmployeeResponse.class);
    }

    private ResponseEntity<String> postEmployee(CreateEmployeeRequest request, String idempotencyKey) {
        return http.exchange("/v1/employees", HttpMethod.POST, new HttpEntity<>(request, headers(idempotencyKey)),
                String.class);
    }

    private HttpHeaders headers(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return headers;
    }

    private int countEmployeesWithEmail(String email) {
        Integer rows = jdbc.queryForObject("SELECT count(*) FROM employee WHERE email = ?", Integer.class, email);
        return rows == null ? -1 : rows;
    }

    private int unpublishedOutboxRowsFor(String email) {
        Integer rows = jdbc.queryForObject("""
                SELECT count(*)
                FROM outbox_event o
                         JOIN employee e ON e.employee_id = o.aggregate_id
                WHERE o.published_at IS NULL
                  AND e.email = ?
                """, Integer.class, email);
        return rows == null ? -1 : rows;
    }

    /**
     * Waits for the event of one specific employee. The relay is asynchronous and the test queue is
     * shared by the whole class, so an event of another test may arrive first; those are discarded
     * instead of failing the assertion.
     */
    private Message receiveEventFor(String email) {
        AtomicReference<Message> found = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            for (int received = 0; received < 10 && found.get() == null; received++) {
                Message message = rabbitTemplate.receive(TEST_QUEUE);
                if (message == null) {
                    break;
                }
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                if (body.contains("\"email\":\"" + email + "\"")) {
                    found.set(message);
                }
            }
            assertThat(found.get()).as("event for %s", email).isNotNull();
        });
        return found.get();
    }

    private void assertPayloadMatches(Message message, String schemaFile) throws IOException {
        try (var schemaSource = Files.newInputStream(PAYLOAD_SCHEMAS.resolve(schemaFile))) {
            var schema = SCHEMAS.getSchema(schemaSource);
            var payload = objectMapper.readTree(message.getBody()).path("data");
            assertThat(schema.validate(payload)).as("payload matches %s", schemaFile).isEmpty();
        }
    }
}
