package dk.ek.shift_happens.leave;

import com.fasterxml.jackson.databind.JsonNode;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * APP_ROLE=query boots with MongoDB and RabbitMQ only: there is no PostgreSQL container in this test, so a DataSource,
 * Flyway or the outbox starting here would fail the context. The mirror image (the command role boots without a Mongo
 * client) is checked in PersistenceFoundationIT.
 */
class LeaveQueryRoleBootIT extends AbstractQueryIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void healthIsPublicAndUp() {
        ResponseEntity<String> response = http.getForEntity("/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void noRelationalOrCommandInfrastructureStartsInTheQueryRole() {
        List<String> beans = new ArrayList<>(List.of(context.getBeanDefinitionNames()));

        assertThat(context.getBeanNamesForType(javax.sql.DataSource.class)).isEmpty();
        assertThat(context.getBeanNamesForType(jakarta.persistence.EntityManagerFactory.class)).isEmpty();
        assertThat(context.getBeanNamesForType(org.flywaydb.core.Flyway.class)).isEmpty();
        assertThat(beans).noneMatch(name -> name.toLowerCase().contains("outbox"))
                .noneMatch(name -> name.toLowerCase().contains("idempotency"))
                .noneMatch(name -> name.toLowerCase().contains("flyway"))
                .noneMatch(name -> name.toLowerCase().contains("hibernate"))
                .noneMatch(name -> name.equals("leaveCommandListener"))
                .noneMatch(name -> name.equals("leaveCommandTopology"));
        assertThat(context.getBeanNamesForType(org.springframework.data.mongodb.core.MongoTemplate.class)).hasSize(1);
        assertThat(beans).contains("leaveQueryListener", "leaveQueryTopology", "leaveQueryController");
    }

    @Test
    void theIndexesAreCreatedAtStartup() {
        assertThat(indexKeys("leave_requests")).contains("employeeId", "requestStatus", "leaveTypeId",
                "decision.leaveApprovalId");
        assertThat(indexKeys("ledger_entries")).contains("employeeId,leaveTypeId");
    }

    @Test
    void thereIsNoWriteEndpointOnTheQueryRole() {
        for (String path : List.of("/v1/leavetypes", "/v1/leaverequests", "/v1/leaveapprovals", "/v1/leaveledgers")) {
            ResponseEntity<JsonNode> post = exchange(HttpMethod.POST, path);
            assertThat(post.getStatusCode()).as("POST " + path).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
            assertThat(post.getHeaders().getContentType().toString()).contains("problem+json");
            assertThat(post.getHeaders().getAllow()).contains(HttpMethod.GET);
        }
        for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (String path : List.of("/v1/leavetypes/1", "/v1/leaverequests/1", "/v1/leaveapprovals/1",
                    "/v1/leaveledgers/1")) {
                ResponseEntity<JsonNode> response = exchange(method, path);
                assertThat(response.getStatusCode()).as(method + " " + path).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
                assertThat(response.getHeaders().getContentType().toString()).contains("problem+json");
            }
        }
    }

    private List<String> indexKeys(String collection) {
        List<String> keys = new ArrayList<>();
        for (Document index : mongo.getCollection(collection).listIndexes()) {
            keys.add(String.join(",", index.get("key", Document.class).keySet()));
        }
        return keys;
    }
}
