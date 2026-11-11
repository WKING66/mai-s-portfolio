package dev.amai.portfolio.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.AuthConstants;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@RestController
@RequestMapping("/api/v1/admin/agent/settings")
@SaCheckRole(AuthConstants.OWNER_ROLE)
public class AgentSettingsController {
    private final AgentAiClient agentAiClient;

    private final boolean featureEnabled;

    public AgentSettingsController(AgentAiClient agentAiClient,
            @Value("${site-guide.enabled:false}") boolean featureEnabled) {
        this.agentAiClient = agentAiClient;
        this.featureEnabled = featureEnabled;
    }

    @GetMapping
    public ResponseEntity<R<?>> read() {
        return relay(agentAiClient::readSettings);
    }

    @PutMapping
    public ResponseEntity<R<?>> update(@RequestBody Map<String, Object> request) {
        // OWNER 写请求仍走网站统一 CSRF 校验。字段与修订冲突由 AI 配置中心统一判断。
        return relay(() -> agentAiClient.updateSettings(request));
    }

    private ResponseEntity<R<?>> relay(Supplier<Map<String, Object>> loadSettings) {
        // 浏览器只接收脱敏结果。上游异常正文可能包含内部配置，不直接透传。
        if (!agentAiClient.isConfigured()) {
            return failure(503, "AI_SETTINGS_UNAVAILABLE", "数字人配置中心尚未连接。");
        }
        try {
            var result = loadSettings.get();
            if (result == null) {
                return failure(503, "AI_SETTINGS_UNAVAILABLE", "数字人配置中心暂不可用。");
            }
            // 功能开关归网站。即使关闭，OWNER 仍可进入设置并完成供应商配置。
            var settings = new LinkedHashMap<>(result);
            settings.put("featureEnabled", featureEnabled);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.success(settings));
        } catch (RestClientResponseException error) {
            return switch (error.getStatusCode().value()) {
                case 409 -> failure(409, "AI_SETTINGS_CONFLICT", "配置已更新或仍有问答运行，请重新读取后再试。");
                case 422 -> failure(422, "AI_SETTINGS_INVALID", "配置值不合法。更换供应商地址时，请同时填写新供应商密钥。");
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
