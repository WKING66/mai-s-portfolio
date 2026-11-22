package dev.amai.portfolio.agent.controller;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.API_PATH;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_ACCEPTED;

import cn.dev33.satoken.annotation.SaCheckLogin;
import dev.amai.portfolio.agent.component.AgentConversationOwnerResolver;
import dev.amai.portfolio.agent.component.AgentEventWriter;
import dev.amai.portfolio.agent.component.AgentStreamExecutor;
import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.entity.request.SubmitRequest;
import dev.amai.portfolio.agent.entity.vo.ConversationId;
import dev.amai.portfolio.agent.service.AgentConversationService;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.AnonymousConversation;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
@RequestMapping(API_PATH)
public class AgentConversationController {
    private final AgentConversationService agentConversationService;

    private final AgentConversationOwnerResolver agentConversationOwnerResolver;

    private final AgentStreamExecutor streams;

    private final JsonMapper jsonMapper;

    public AgentConversationController(AgentConversationService agentConversationService,
            AgentConversationOwnerResolver agentConversationOwnerResolver, AgentStreamExecutor streams,
            JsonMapper jsonMapper) {
        this.agentConversationService = agentConversationService;
        this.agentConversationOwnerResolver = agentConversationOwnerResolver;
        this.streams = streams;
        this.jsonMapper = jsonMapper;
    }

    @GetMapping("/status")
    public R<Map<String, Boolean>> status() {
        return R.success(Map.of("enabled", agentConversationService.enabled()));
    }

    @PostMapping("/conversations")
    @AnonymousConversation
    public R<ConversationId> create(HttpServletRequest request, HttpServletResponse response) {
        var owner = agentConversationOwnerResolver.resolve(request, response);
        return R.success(new ConversationId(agentConversationService.create(owner.key(), owner.anonymous())));
    }

    @GetMapping("/conversations")
    @SaCheckLogin
    public R<AgentConversationService.ConversationPage> list(
            @RequestParam(required = false) String cursor) {
        return R.success(agentConversationService.list(agentConversationOwnerResolver.accountOwner(), cursor == null ? null : positiveId(cursor)));
    }

    @PostMapping("/conversations/claim")
    @SaCheckLogin
    public R<ConversationId> claimAnonymous(HttpServletRequest request) {
        String id = agentConversationService.claimAnonymous(
                agentConversationOwnerResolver.visitorOwner(request), agentConversationOwnerResolver.accountOwner());
        return R.success(new ConversationId(id));
    }

    @GetMapping("/conversations/{id}")
    @AnonymousConversation
    public R<AgentConversationService.ConversationDetails> details(
            @PathVariable String id, HttpServletRequest request) {
        return R.success(agentConversationService.details(positiveId(id), agentConversationOwnerResolver.resolve(request, null).key()));
    }

    @PostMapping("/conversations/{id}/clear")
    @AnonymousConversation
    public R<AgentConversationService.ConversationDetails> clear(
            @PathVariable String id, HttpServletRequest request) {
        var owner = agentConversationOwnerResolver.resolve(request, null);
        return R.success(agentConversationService.clear(positiveId(id), owner.key()));
    }

    @PostMapping(value = "/conversations/{id}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @AnonymousConversation
    public StreamingResponseBody submit(@PathVariable String id,
            @Valid @RequestBody SubmitRequest input, HttpServletRequest request, HttpServletResponse response) {
        var owner = agentConversationOwnerResolver.resolve(request, null);
        long conversationId = positiveId(id);
        streams.select(request);
        // 专用线程可用后才开始受理。拒绝时响应尚未开流，可返回原业务状态码。
        return output -> streams.runSubmission(() -> stream(output, response, true,
                () -> agentConversationService.submit(conversationId, owner.key(), owner.anonymous(),
                        input.requestId().toLowerCase(Locale.ROOT), input.question())));
    }

    @GetMapping("/executions/{id}")
    @AnonymousConversation
    public R<AgentExecutionSnapshot> snapshot(@PathVariable String id, HttpServletRequest request) {
        var turn = agentConversationService.ownedExecution(positiveId(id), agentConversationOwnerResolver.resolve(request, null).key());
        return R.success(agentConversationService.snapshot(turn));
    }

    @GetMapping(value = "/executions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @AnonymousConversation
    public StreamingResponseBody events(@PathVariable String id, HttpServletRequest request,
            HttpServletResponse response) {
        var turn = agentConversationService.ownedExecution(positiveId(id), agentConversationOwnerResolver.resolve(request, null).key());
        streams.select(request);
        return output -> stream(output, response, false, () -> turn);
    }

    @PostMapping("/executions/{id}/stop")
    @AnonymousConversation
    public R<AgentExecutionSnapshot> stop(@PathVariable String id, HttpServletRequest request) {
        var turn = agentConversationService.ownedExecution(positiveId(id), agentConversationOwnerResolver.resolve(request, null).key());
        return R.success(agentConversationService.stop(turn));
    }

    private static long positiveId(String id) {
        try {
            long parsed = Long.parseLong(id);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 旧会话编号和无效编号统一按不存在处理，浏览器可用访客 Cookie 找回当前会话。
        }
        throw new ApiException(ApiErrorCode.NOT_FOUND, "会话或执行不存在。");
    }

    private void stream(OutputStream output, HttpServletResponse response, boolean sendReceipt,
            Supplier<AgentTurn> loadTurn) {
        var turn = loadTurn.get();
        response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        response.setHeader("X-Accel-Buffering", "no");

        var writer = new AgentEventWriter(output, response);
        try {
            if (sendReceipt) {
                var receipt = agentConversationService.receipt(turn);
                writer.write(EVENT_ACCEPTED, jsonMapper.writeValueAsString(receipt));
            }
            agentConversationService.events(turn, writer::write, writer::keepAlive);
        } catch (AgentEventWriter.Disconnected ignored) {
            // 断线只结束订阅，定时任务会补存执行终态。
        }
    }
}
