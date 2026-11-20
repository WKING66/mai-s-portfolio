package dev.amai.portfolio.agent.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.agent.entity.AgentSiteSettings;
import dev.amai.portfolio.agent.entity.request.UpdateAgentSiteSettings;
import dev.amai.portfolio.agent.service.AgentSiteSettingsService;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.AuthConstants;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    private final AgentSiteSettingsService agentSiteSettingsService;

    public AgentSettingsController(AgentAiClient agentAiClient,
            AgentSiteSettingsService agentSiteSettingsService) {
        this.agentAiClient = agentAiClient;
        this.agentSiteSettingsService = agentSiteSettingsService;
    }

    @GetMapping("/site")
    public R<AgentSiteSettings> readSite() {
        return R.success(agentSiteSettingsService.read());
    }

    @PutMapping("/site")
    public R<AgentSiteSettings> updateSite(@Valid @RequestBody UpdateAgentSiteSettings request) {
        return R.success(agentSiteSettingsService.save(request));
    }

    @GetMapping("/catalog")
    public ResponseEntity<R<?>> readCatalog() {
        return relay(agentAiClient::readCatalog);
    }

    @GetMapping("/catalog/wallets")
    public ResponseEntity<R<?>> readWallets() {
        return relay(() -> agentAiClient.readCatalog("/wallets"));
    }

    @PostMapping("/catalog/providers")
    public ResponseEntity<R<?>> addProvider(@RequestBody Map<String, Object> request) {
        return relay(() -> agentAiClient.writeCatalog(HttpMethod.POST, "/providers", request));
    }

    @PutMapping("/catalog/providers/{id}")
    public ResponseEntity<R<?>> updateProvider(@PathVariable long id,
            @RequestBody Map<String, Object> request) {
        return relay(() -> agentAiClient.writeCatalog(HttpMethod.PUT, "/providers/{id}", request, id));
    }

    @PostMapping("/catalog/models")
    public ResponseEntity<R<?>> addModel(@RequestBody Map<String, Object> request) {
        return relay(() -> agentAiClient.writeCatalog(HttpMethod.POST, "/models", request));
    }

    @PutMapping("/catalog/models/{id}")
    public ResponseEntity<R<?>> updateModel(@PathVariable long id,
            @RequestBody Map<String, Object> request) {
        return relay(() -> agentAiClient.writeCatalog(HttpMethod.PUT, "/models/{id}", request, id));
    }

    @PostMapping("/catalog/models/{id}/test")
    public ResponseEntity<R<?>> testModel(@PathVariable long id) {
        return relay(() -> agentAiClient.testModel(id));
    }

    @PutMapping("/catalog/routes/{type}")
    public ResponseEntity<R<?>> replaceRoute(@PathVariable int type,
            @RequestBody Map<String, Object> request) {
        return relay(() -> agentAiClient.writeCatalog(HttpMethod.PUT, "/routes/{type}", request, type));
    }

    @GetMapping("/catalog/prompts")
    public ResponseEntity<R<?>> readPrompts() {
        return relay(() -> agentAiClient.readCatalog("/prompts"));
    }

    @GetMapping("/catalog/prompts/{id}/versions")
    public ResponseEntity<R<?>> readPromptVersions(@PathVariable long id) {
        return relay(() -> agentAiClient.readCatalog("/prompts/{id}/versions", id));
    }

    @PostMapping("/catalog/prompts/{id}/versions")
    public ResponseEntity<R<?>> publishPromptVersion(@PathVariable long id,
            @RequestBody Map<String, Object> request) {
        return relay(() -> agentAiClient.writeCatalog(HttpMethod.POST, "/prompts/{id}/versions", request, id));
    }

    @PutMapping("/catalog/prompts/{id}/active-version")
    public ResponseEntity<R<?>> activatePromptVersion(@PathVariable long id,
            @RequestBody Map<String, Object> request) {
        return relay(() -> agentAiClient.writeCatalog(HttpMethod.PUT, "/prompts/{id}/active-version", request, id));
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
            settings.put("featureEnabled", agentSiteSettingsService.read().agentEnabled());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(R.success(settings));
        } catch (RestClientResponseException error) {
            return switch (error.getStatusCode().value()) {
                case 404 -> failure(404, "AI_SETTINGS_NOT_FOUND", "配置已不存在，请刷新后再试。");
                case 409 -> failure(409, "AI_SETTINGS_CONFLICT", "配置冲突，请重新读取并核对后再试。");
                case 422 -> failure(422, "AI_SETTINGS_INVALID", "无法保存，请检查模型、链顺序及供应商接入参数。");
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
