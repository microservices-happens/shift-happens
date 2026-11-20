package dk.ek.shift_happens.scheduling.api.graphql;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring for GraphQL needs the SDL on the classpath, so the service ships a copy of
 * contracts/graphql/scheduling.graphql. This test fails the moment the copy and the contract disagree,
 * which keeps the contract the single source of truth.
 */
class GraphQlSchemaMatchesContractTest {

    @Test
    void theServedSchemaIsTheContract() throws IOException {
        Path basedir = Path.of(System.getProperty("basedir", "."));
        Path contract = basedir.resolve("../../contracts/graphql/scheduling.graphql");
        Path served = basedir.resolve("src/main/resources/graphql/scheduling.graphqls");

        assertThat(contract).as("contract file").exists();
        assertThat(served).as("served schema").exists();
        assertThat(definitions(served)).isEqualTo(definitions(contract));
    }

    /** Comments and blank lines are documentation, not schema: they may differ between the two files. */
    private static List<String> definitions(Path file) throws IOException {
        return Files.readAllLines(file).stream()
                .map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .filter(line -> !line.stripLeading().startsWith("#"))
                .toList();
    }
}
