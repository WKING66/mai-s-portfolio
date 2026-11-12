package dev.amai.portfolio.agent.controller;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.API_PATH;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_ACCEPTED;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.IDENTIFIER_PATTERN;

import cn.dev33.satoken.annotation.SaCheckLogin;
import dev.amai.portfolio.agent.component.AgentConversationOwnerResolver;
import dev.amai.portfolio.agent.component.AgentEventWriter;
import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.entity.request.ImportRequest;
import dev.amai.portfolio.agent.entity.request.SubmitRequest;
import dev.amai.portfolio.agent.entity.vo.ConversationId;
import dev.amai.portfolio.agent.service.AgentConversationService;
import dev.amai.portfolio.common.R;
import dev.amai.portfolio.system.api.AnonymousConversation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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
@RequestMapping(API_PATH)
public class AgentConversationController {
    private final AgentConversationService agentConversationService;

    private final AgentConversationOwnerResolver agentConversationOwnerResolver;

    private final JsonMapper jsonMapper;

    public AgentConversationController(AgentConversationService agentConversationService,
            AgentConversationOwnerResolver agentConversationOwnerResolver, JsonMapper jsonMapper) {
        this.agentConversationService = agentConversationService;
        this.agentConversationOwnerResolver = agentConversationOwnerResolver;
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
            @RequestParam(required = false) @Pattern(regexp = IDENTIFIER_PATTERN) String cursor) {
        return R.success(agentConversationService.list(agentConversationOwnerResolver.accountOwner(), canonicalId(cursor)));
    }

    @GetMapping("/conversations/{id}")
    @AnonymousConversation
    public R<AgentConversationService.ConversationDetails> details(
            @PathVariable @Pattern(regexp = IDENTIFIER_PATTERN) String id, HttpServletRequest request) {
        return R.success(agentConversationService.details(canonicalId(id), agentConversationOwnerResolver.resolve(request, null).key()));
    }

    @PostMapping("/conversations/import")
    @SaCheckLogin
    public R<ConversationId> importConversation(@Valid @RequestBody ImportRequest input) {
        return R.success(new ConversationId(agentConversationService.importConversation(
                agentConversationOwnerResolver.accountOwner(), canonicalId(input.importId()), input.turns())));
    }

    @PostMapping(value = "/conversations/{id}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @AnonymousConversation
    public ResponseEntity<StreamingResponseBody> submit(@PathVariable @Pattern(regexp = IDENTIFIER_PATTERN) String id,
            @Valid @RequestBody SubmitRequest input, HttpServletRequest request) {
        var owner = agentConversationOwnerResolver.resolve(request, null);
        var turn = agentConversationService.submit(
                canonicalId(id), owner.key(), owner.anonymous(), canonicalId(input.requestId()), input.question());
        return stream(turn, true);
    }

    @GetMapping("/executions/{id}")
    @AnonymousConversation
    public R<AgentExecutionSnapshot> snapshot(@PathVariable @Pattern(regexp = IDENTIFIER_PATTERN) String id, HttpServletRequest request) {
        var turn = agentConversationService.ownedExecution(canonicalId(id), agentConversationOwnerResolver.resolve(request, null).key());
        return R.success(agentConversationService.snapshot(turn));
    }

    @GetMapping(value = "/executions/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @AnonymousConversation
    public ResponseEntity<StreamingResponseBody> events(@PathVariable @Pattern(regexp = IDENTIFIER_PATTERN) String id, HttpServletRequest request) {
        var turn = agentConversationService.ownedExecution(canonicalId(id), agentConversationOwnerResolver.resolve(request, null).key());
        return stream(turn, false);
    }

    @PostMapping("/executions/{id}/stop")
    @AnonymousConversation
    public R<AgentExecutionSnapshot> stop(@PathVariable @Pattern(regexp = IDENTIFIER_PATTERN) String id, HttpServletRequest request) {
        var turn = agentConversationService.ownedExecution(canonicalId(id), agentConversationOwnerResolver.resolve(request, null).key());
        return R.success(agentConversationService.stop(turn));
    }

    private static String canonicalId(String id) {
        return id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    private ResponseEntity<StreamingResponseBody> stream(AgentTurn turn, boolean sendReceipt) {
        StreamingResponseBody body = output -> {
            var writer = new AgentEventWriter(output);
            if (sendReceipt) {
                var receipt = agentConversationService.receipt(turn);
                writer.write(EVENT_ACCEPTED, jsonMapper.writeValueAsString(receipt));
            }
            agentConversationService.events(turn, writer::write);
        };
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(body);
    }
}
