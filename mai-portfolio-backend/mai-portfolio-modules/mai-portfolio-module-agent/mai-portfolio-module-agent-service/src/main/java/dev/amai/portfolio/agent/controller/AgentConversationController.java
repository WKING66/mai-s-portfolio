package dev.amai.portfolio.agent.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.stp.StpUtil;
import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.service.AgentConversationService;
import dev.amai.portfolio.agent.service.AgentConversationService.ImportedTurn;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.redis.rate.RedisRateLimiter;
import dev.amai.portfolio.security.SecurityMessageConstants;
import dev.amai.portfolio.security.context.AccountContext;
import dev.amai.portfolio.system.api.AnonymousConversation;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/agent/v1")
public class AgentConversationController {
    private static final String VISITOR_COOKIE = "portfolio_agent_visitor";
    private static final String VISITOR_SEND_RATE_KEY = "agent:visitor:send";
    private static final int VISITOR_SENDS_PER_MINUTE = 12;

    private final AgentConversationService agentConversationService;

    private final JsonMapper jsonMapper;

    private final RedisRateLimiter redisRateLimiter;

    public AgentConversationController(AgentConversationService agentConversationService,
            JsonMapper jsonMapper, RedisRateLimiter redisRateLimiter) {
        this.agentConversationService = agentConversationService;
        this.jsonMapper = jsonMapper;
        this.redisRateLimiter = redisRateLimiter;
    }

    @GetMapping("/status")
    public R<Map<String, Boolean>> status() {
        return R.success(Map.of("enabled", agentConversationService.enabled()));
    }

    @PostMapping("/conversations")
    @AnonymousConversation
    public R<ConversationId> create(HttpServletRequest request, HttpServletResponse response) {
        var owner = owner(request, response);
        return R.success(new ConversationId(agentConversationService.create(owner.key(), owner.anonymous())));
    }

    @GetMapping("/conversations")
    @SaCheckLogin
    public R<AgentConversationService.ConversationPage> list(@RequestParam(required = false) UUID cursor) {
        return R.success(agentConversationService.list(accountOwner(), cursor));
    }

    @GetMapping("/conversations/{id}")
    @AnonymousConversation
    public R<AgentConversationService.ConversationDetails> details(@PathVariable UUID id, HttpServletRequest request) {
        return R.success(agentConversationService.details(id, owner(request, null).key()));
    }

    @PostMapping("/conversations/import")
    @SaCheckLogin
    public R<ConversationId> importConversation(@Valid @RequestBody ImportRequest input) {
        return R.success(new ConversationId(agentConversationService.importConversation(
                accountOwner(), input.importId(), input.turns())));
    }

    @PostMapping(value = "/conversations/{id}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @AnonymousConversation
    public ResponseEntity<StreamingResponseBody> submit(@PathVariable UUID id,
            @Valid @RequestBody SubmitRequest input, HttpServletRequest request) {
        var owner = owner(request, null);
        // 匿名发送按容器识别的来源计数。切换会话不会重置额度，避免挤占公共模型容量。
        if (owner.anonymous() && !redisRateLimiter.fixedWindow(
                VISITOR_SEND_RATE_KEY, request.getRemoteAddr(), VISITOR_SENDS_PER_MINUTE, 60)) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED, SecurityMessageConstants.RATE_LIMITED);
        }
        var turn = agentConversationService.submit(id, owner.key(), input.requestId(), input.question());
        return stream(turn, true);
    }

    @GetMapping("/executions/{id}")
    @AnonymousConversation
    public R<AgentExecutionSnapshot> snapshot(@PathVariable UUID id, HttpServletRequest request) {
        var turn = agentConversationService.ownedExecution(id, owner(request, null).key());
        return R.success(agentConversationService.snapshot(turn));
    }

    @GetMapping(value = "/executions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @AnonymousConversation
    public ResponseEntity<StreamingResponseBody> events(@PathVariable UUID id, HttpServletRequest request) {
        var turn = agentConversationService.ownedExecution(id, owner(request, null).key());
        return stream(turn, false);
    }

    @PostMapping("/executions/{id}/stop")
    @AnonymousConversation
    public R<AgentExecutionSnapshot> stop(@PathVariable UUID id, HttpServletRequest request) {
        var turn = agentConversationService.ownedExecution(id, owner(request, null).key());
        return R.success(agentConversationService.stop(turn));
    }

    private ResponseEntity<StreamingResponseBody> stream(AgentTurn turn, boolean sendReceipt) {
        StreamingResponseBody body = output -> {
            var writer = new AgentEventWriter(output);
            if (sendReceipt) {
                var receipt = agentConversationService.receipt(turn);
                writer.write("accepted", jsonMapper.writeValueAsString(receipt));
            }
            agentConversationService.events(turn, writer::write);
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(body);
    }

    private Owner owner(HttpServletRequest request, HttpServletResponse response) {
        if (StpUtil.isLogin()) {
            return new Owner(accountOwner(), false);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (VISITOR_COOKIE.equals(cookie.getName())) {
                    try {
                        return new Owner("visitor:" + UUID.fromString(cookie.getValue()), true);
                    } catch (IllegalArgumentException error) {
                        break;
                    }
                }
            }
        }
        // Cookie 不存在时只允许初始化，不凭 URL 中的会话编号建立匿名身份。
        if (response == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "会话或执行不存在。");
        }
        UUID id = UUID.randomUUID();
        var cookie = ResponseCookie.from(VISITOR_COOKIE, id.toString())
                .httpOnly(true).secure(request.isSecure()).sameSite("Strict")
                .path("/api/agent/v1").maxAge(Duration.ofDays(7)).build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return new Owner("visitor:" + id, true);
    }

    private static String accountOwner() {
        return "account:" + AccountContext.currentAccount().id();
    }

    private record Owner(
            String key,

            boolean anonymous) {}

    public record ConversationId(UUID conversationId) {}

    public record SubmitRequest(
            @NotNull
            UUID requestId,

            @NotBlank
            @Size(max = 4000)
            String question) {}

    public record ImportRequest(
            @NotNull
            UUID importId,

            @NotNull
            @Size(min = 1, max = 20)
            List<@NotNull @Valid ImportedTurn> turns) {}

    private static final class AgentEventWriter {
        private final OutputStream output;

        private boolean disconnected;

        private AgentEventWriter(OutputStream output) {
            this.output = output;
        }

        private void write(String event, String json) {
            if (disconnected) {
                return;
            }
            try {
                output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
                for (String line : json.split("\\r\\n|\\r|\\n", -1)) {
                    output.write(("data: " + line + "\n").getBytes(StandardCharsets.UTF_8));
                }
                output.write('\n');
                output.flush();
            } catch (IOException error) {
                // 浏览器离开只停止写出，继续消费本轮终态并持久化网站记录。
                disconnected = true;
            }
        }
    }
}
