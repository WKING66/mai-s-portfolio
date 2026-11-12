package dev.amai.portfolio.agent.client;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EXECUTION_NOT_FOUND;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.SSE_DATA_PREFIX;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.SSE_EVENT_PREFIX;

import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class AgentAiClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration EXECUTION_READ_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration SETTINGS_READ_TIMEOUT = Duration.ofSeconds(5);

    private static final String PATH = "/internal/v1/executions";
    private static final String SETTINGS_PATH = "/internal/v1/settings";
    private static final ParameterizedTypeReference<Map<String, Object>> SETTINGS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient client;
    private final RestClient settingsClient;
    private final boolean configured;

    private final JsonMapper jsonMapper;

    public AgentAiClient(@Value("${site-guide.ai-base-url}") String baseUrl,
            @Value("${site-guide.ai-server-token:}") String token, JsonMapper jsonMapper) {
        var http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
        this.client = createClient(baseUrl, token, http, EXECUTION_READ_TIMEOUT);
        this.settingsClient = createClient(baseUrl, token, http, SETTINGS_READ_TIMEOUT);
        this.configured = !token.isBlank();
        this.jsonMapper = jsonMapper;
    }

    private static RestClient createClient(String baseUrl, String token, HttpClient http,
            Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .defaultHeaders(headers -> headers.setBearerAuth(token))
                .build();
    }

    public boolean isConfigured() {
        return configured;
    }

    public Map<String, Object> readSettings() {
        return settingsClient.get().uri(SETTINGS_PATH).retrieve().body(SETTINGS_TYPE);
    }

    public Map<String, Object> updateSettings(Map<String, Object> request) {
        return settingsClient.put().uri(SETTINGS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve().body(SETTINGS_TYPE);
    }

    public Accepted submit(Submission submission) {
        return client.post().uri(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(submission)
                .retrieve().body(Accepted.class);
    }

    public AgentExecutionSnapshot byRequest(String requestId) {
        return lookup(PATH + "?requestId={id}", requestId);
    }

    public AgentExecutionSnapshot findExecution(String executionId) {
        return lookup(PATH + "/{id}", executionId);
    }

    private AgentExecutionSnapshot lookup(String uri, String id) {
        try {
            var snapshot = client.get().uri(uri, id).retrieve().body(AgentExecutionSnapshot.class);
            if (snapshot == null) {
                throw new RestClientException("AI execution lookup returned no response body");
            }
            return snapshot;
        } catch (HttpClientErrorException.NotFound error) {
            // 代理或地址错配也可能返回 404。只有 Python 确认执行不存在才允许写失败终态。
            var rejection = error.getResponseBodyAs(AgentExecutionSnapshot.ExecutionError.class);
            if (rejection != null && EXECUTION_NOT_FOUND.equals(rejection.code())) {
                return null;
            }
            throw error;
        }
    }

    public AgentExecutionSnapshot get(String executionId) {
        return client.get().uri(PATH + "/{id}", executionId)
                .retrieve().body(AgentExecutionSnapshot.class);
    }

    public AgentExecutionSnapshot stop(String executionId) {
        return client.post().uri(PATH + "/{id}/stop", executionId)
                .retrieve().body(AgentExecutionSnapshot.class);
    }

    public void events(String executionId, BiConsumer<String, String> receive) {
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
                            } else if (line.startsWith(SSE_EVENT_PREFIX)) {
                                event = line.substring(SSE_EVENT_PREFIX.length());
                            } else if (line.startsWith(SSE_DATA_PREFIX)) {
                                if (!data.isEmpty()) {
                                    data.append('\n');
                                }
                                data.append(line.substring(SSE_DATA_PREFIX.length()));
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
            String executionId,

            String status) {}

    public record History(
            String role,

            String text) {}

    public record Submission(
            String requestId,

            String question,

            List<History> history,

            OffsetDateTime acceptBefore) {}
}
