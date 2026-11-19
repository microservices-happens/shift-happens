package dk.ek.shift_happens.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P8.3: GraphiQL is enabled on the query role through the dev profile, which is how a developer explores
 * contracts/graphql/scheduling.graphql without writing a client first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"query", "dev"})
class SchedulingGraphiqlIT extends AbstractQueryIT {

    @Test
    void graphiqlIsServedWhenTheDevProfileIsActive() {
        ResponseEntity<String> response = http.getForEntity("/graphiql", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsIgnoringCase("graphiql");
    }
}
