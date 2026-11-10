package dev.amai.portfolio.agent.client;

import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AgentAiClient {
    private static final String PATH = "/internal/v1/executions";

    private final RestClient client;

    private final JsonMapper jsonMapper;

    public AgentAiClient(@Value("${site-guide.ai-base-url}") String baseUrl,
            @Value("${site-guide.ai-server-token:}") String token, JsonMapper jsonMapper) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(60));
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(token))
                .build();
        this.jsonMapper = jsonMapper;
    }

    public Accepted submit(Submission submission) {
        return client.post().uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(submission)
                .retrieve().body(Accepted.class);
    }

    public AgentExecutionSnapshot byRequest(UUID requestId) {
        try {
            return client.get().uri(PATH + "?requestId={id}", requestId)
                    .retrieve().body(AgentExecutionSnapshot.class);
        } catch (RestClientResponseException error) {
            if (error.getStatusCode().value() == 404) {
                return null;
            }
            throw error;
        }
    }

    public AgentExecutionSnapshot get(UUID executionId) {
        return client.get().uri(PATH + "/{id}", executionId)
                .retrieve().body(AgentExecutionSnapshot.class);
    }

    public AgentExecutionSnapshot stop(UUID executionId) {
        return client.post().uri(PATH + "/{id}/stop", executionId)
                .retrieve().body(AgentExecutionSnapshot.class);
    }

    public void events(UUID executionId, BiConsumer<String, String> receive) {
        client.get().uri(PATH + "/{id}/events", executionId)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("AI execution stream unavailable");
                    }
                    // 上游仅有结构化事件；空行才是事件边界，不能把网络分块当成完整事件。
                    try (var reader = new BufferedReader(new InputStreamReader(
                            response.getBody(), StandardCharsets.UTF_8))) {
                        String event = null;
                        var data = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.isEmpty()) {
                                if (event != null && !data.isEmpty()) {
                                    receive.accept(event, data.toString());
                                }
                                event = null;
                                data.setLength(0);
                            } else if (line.startsWith("event: ")) {
                                event = line.substring(7);
                            } else if (line.startsWith("data: ")) {
                                if (!data.isEmpty()) {
                                    data.append('\n');
                                }
                                data.append(line.substring(6));
                            }
                        }
                    }
                    return null;
                });
    }

    public String json(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    public AgentExecutionSnapshot snapshot(String data) {
        return jsonMapper.readValue(data, AgentExecutionSnapshot.class);
    }

    public record Accepted(
            UUID executionId,

            String status) {}

    public record History(
            String role,

            String text) {}

    public record Submission(
            UUID requestId,

            String question,

            List<History> history,

            OffsetDateTime acceptBefore) {}
}
