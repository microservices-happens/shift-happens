package dk.ek.shift_happens.ai.infrastructure.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OllamaClient {
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;

    public OllamaClient(ObjectMapper mapper, @Value("${app.llm.base-url}") String baseUrl,
                        @Value("${app.llm.model}") String model,
                        @Value("${app.llm.timeout}") Duration timeout) {
        this.mapper = mapper;
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/api/generate");
        this.model = model;
        this.timeout = timeout;
    }

    public String model() { return model; }

    public String generate(String prompt, List<Long> allowedIds) throws Exception {
        Map<String, Object> candidateSchema = Map.of(
                "type", "object", "additionalProperties", false,
                "required", List.of("employeeId", "rank"),
                "properties", Map.of(
                        "employeeId", Map.of("type", "integer", "enum", allowedIds),
                        "rank", Map.of("type", "integer", "minimum", 1, "maximum", allowedIds.size())));
        Map<String, Object> schema = Map.of(
                "type", "object", "additionalProperties", false,
                "required", List.of("rankedCandidates", "reason"),
                "properties", Map.of(
                        "rankedCandidates", Map.of("type", "array", "minItems", allowedIds.size(),
                                "maxItems", allowedIds.size(), "items", candidateSchema),
                        "reason", Map.of("type", "string", "minLength", 1)));
        String body = mapper.writeValueAsString(Map.of("model", model, "prompt", prompt,
                "format", schema, "stream", false, "options", Map.of("temperature", 0)));
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("Ollama returned " + response.statusCode());
        JsonNode result = mapper.readTree(response.body());
        if (!result.path("response").isTextual()) throw new IllegalArgumentException("Missing model response");
        return result.path("response").asText();
    }
}
