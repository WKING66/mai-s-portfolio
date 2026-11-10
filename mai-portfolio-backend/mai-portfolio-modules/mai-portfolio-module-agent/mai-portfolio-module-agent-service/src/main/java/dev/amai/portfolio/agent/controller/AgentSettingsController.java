package dev.amai.portfolio.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.AuthConstants;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@RestController
@RequestMapping("/api/v1/admin/agent/settings")
@SaCheckRole(AuthConstants.OWNER_ROLE)
public class AgentSettingsController {
    private static final String SETTINGS_PATH = "/internal/v1/settings";

    private static final ParameterizedTypeReference<Map<String, Object>> SETTINGS_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient aiSettingsClient;

    private final boolean configured;

    public AgentSettingsController(
            @Value("${site-guide.ai-base-url:http://127.0.0.1:9344}") String baseUrl,
            @Value("${site-guide.ai-management-token:${AI_MANAGEMENT_TOKEN:}}") String token) {
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        this.configured = !token.isBlank();
        this.aiSettingsClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBearerAuth(token))
                .build();
    }

    @GetMapping
    public ResponseEntity<R<?>> read() {
        return relay(aiSettingsClient.get().uri(SETTINGS_PATH));
    }

    @PutMapping
    public ResponseEntity<R<?>> update(@RequestBody Map<String, Object> request) {
        // OWNER 写请求仍走网站统一 CSRF 校验。字段与修订冲突由 AI 配置中心统一判断。
        return relay(aiSettingsClient.put().uri(SETTINGS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request));
    }

    private ResponseEntity<R<?>> relay(RestClient.RequestHeadersSpec<?> request) {
        // 浏览器只接收脱敏结果。上游异常正文可能包含内部配置，不直接透传。
        if (!configured) {
            return failure(503, "AI_SETTINGS_UNAVAILABLE", "数字人配置中心尚未连接。");
        }
        try {
            var result = request.retrieve().body(SETTINGS_TYPE);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.success(result));
        } catch (RestClientResponseException error) {
            return switch (error.getStatusCode().value()) {
                case 409 -> failure(409, "AI_SETTINGS_CONFLICT", "配置已更新或仍有问答运行，请重新读取后再试。");
                case 422 -> failure(422, "AI_SETTINGS_INVALID", "配置值不合法。更换供应商地址时，请关闭收费并填写新密钥。");
                default -> failure(503, "AI_SETTINGS_UNAVAILABLE", "数字人配置中心暂不可用。");
            };
        } catch (RestClientException error) {
            return failure(503, "AI_SETTINGS_UNAVAILABLE", "数字人配置中心暂不可用。");
        }
    }

    private ResponseEntity<R<?>> failure(int status, String code, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(R.failure(code, message, List.of()));
    }
}
