package dev.amai.portfolio.agent.controller;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.IDENTIFIER_PATTERN;

import dev.amai.portfolio.agent.entity.request.ModelBudgetReservation;
import dev.amai.portfolio.agent.entity.request.ModelBudgetSettlement;
import dev.amai.portfolio.agent.service.impl.AgentModelBudgetService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 模型执行服务在实际外呼前申请金额预占，在供应商返回用量后提交结算。 */
@RestController
@RequestMapping("/internal/v1/site-guide/budget")
public class AgentModelBudgetController {
    private final AgentModelBudgetService agentModelBudgetService;

    private final String serverToken;

    public AgentModelBudgetController(AgentModelBudgetService agentModelBudgetService,
            @Value("${site-guide.ai-server-token:}") String serverToken) {
        this.agentModelBudgetService = agentModelBudgetService;
        this.serverToken = serverToken;
    }

    @PostMapping("/reservations")
    public ResponseEntity<Void> reserve(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid
            @RequestBody
            ModelBudgetReservation request) {
        requireServiceToken(authorization);
        agentModelBudgetService.reserve(request);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/reservations/{attemptId}")
    public ResponseEntity<Void> release(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @PathVariable
            @Pattern(regexp = IDENTIFIER_PATTERN)
            String attemptId) {
        requireServiceToken(authorization);
        agentModelBudgetService.release(attemptId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/settlements")
    public ResponseEntity<Void> settle(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid
            @RequestBody
            ModelBudgetSettlement request) {
        requireServiceToken(authorization);
        return agentModelBudgetService.settle(request)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    private void requireServiceToken(String authorization) {
        // 只接受 Python 服务凭证，避免网站账号请求改写全站费用账本。
        if (serverToken.isBlank() || authorization == null || !MessageDigest.isEqual(
                ("Bearer " + serverToken).getBytes(StandardCharsets.UTF_8),
                authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, "无权访问模型预算接口。");
        }
    }
}
