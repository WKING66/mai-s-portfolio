package dev.amai.portfolio.agent.client;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.json.JsonMapper;

class AgentAiClientCapacityTest {
    @Test
    void pythonCapacityRejectionMayOmitRequestId() throws IOException {
        assertRejection("CAPACITY_EXHAUSTED", AgentAiClient.CapacityRejected.class);
    }

    @Test
    void otherTooManyRequestsResponsesRemainUnconfirmed() throws IOException {
        assertRejection("OTHER_LIMIT", HttpClientErrorException.TooManyRequests.class);
    }

    private static void assertRejection(String code, Class<? extends Throwable> expected) throws IOException {
        var python = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        python.createContext("/internal/v1/executions", exchange -> {
            try (exchange) {
                exchange.getRequestBody().readAllBytes();
                byte[] response = ("{\"code\":\"" + code + "\",\"message\":\"Chat is busy.\",\"requestId\":null}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(429, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        python.start();
        try {
            var client = new AgentAiClient("http://127.0.0.1:" + python.getAddress().getPort(), "test-token",
                    JsonMapper.builder().build());
            var submission = new AgentAiClient.Submission("46abe276-08b6-4d59-8d3d-7822920dcfac", "问题",
                    List.of(), OffsetDateTime.now().plusSeconds(45));
            assertThrows(expected, () -> client.submit(submission));
        } finally {
            python.stop(0);
        }
    }
}
