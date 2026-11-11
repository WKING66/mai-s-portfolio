package dev.amai.portfolio.agent.service.impl;

import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.agent.mapper.AgentConversationMapper;
import dev.amai.portfolio.agent.service.AgentConversationService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgentConversationServiceImpl implements AgentConversationService {
    private static final short PENDING = 0;
    private static final short ACCEPTED = 1;
    private static final short COMPLETED = 3;
    private static final short FAILED = 4;
    private static final short INTERRUPTED = 5;
    private static final String EXPIRED_TEXT = "EXECUTION_TEXT_EXPIRED";
    private static final String UNAVAILABLE = "模型服务暂不可用。";

    private final AgentConversationMapper agentConversationMapper;

    private final AgentAiClient agentAiClient;

    private final TransactionTemplate transactionTemplate;

    private final boolean enabled;

    public AgentConversationServiceImpl(AgentConversationMapper agentConversationMapper,
            AgentAiClient agentAiClient, TransactionTemplate transactionTemplate,
            @Value("${site-guide.enabled:false}") boolean enabled) {
        this.agentConversationMapper = agentConversationMapper;
        this.agentAiClient = agentAiClient;
        this.transactionTemplate = transactionTemplate;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public UUID create(String owner, boolean anonymous) {
        UUID inserted = agentConversationMapper.insertConversation(UUID.randomUUID(), owner, anonymous, null);
        // 匿名身份在并发初始化时共享数据库唯一键；回包丢失也会查到原会话。
        if (inserted == null && anonymous) {
            return agentConversationMapper.findAnonymous(owner);
        }
        return inserted;
    }

    public ConversationPage list(String owner, UUID cursor) {
        var rows = agentConversationMapper.list(owner, cursor, 21);
        var items = rows.stream()
                .limit(20)
                .map(row -> new ConversationSummary(row.id(), row.updatedAt()))
                .toList();
        UUID nextCursor = rows.size() > 20 ? items.getLast().conversationId() : null;
        return new ConversationPage(items, nextCursor);
    }

    public ConversationDetails details(UUID id, String owner) {
        requireFound(agentConversationMapper.findConversation(id, owner));
        var rows = agentConversationMapper.turns(id, 100);
        var turns = rows.reversed().stream()
                .map(turn -> new TurnView(turn.requestId(), turn.executionId(), turn.question(), turn.answer(),
                        status(turn.status())))
                .toList();
        return new ConversationDetails(id, turns);
    }

    public UUID importConversation(String owner, UUID importId, List<ImportedTurn> turns) {
        return transactionTemplate.execute(transaction -> {
            UUID id = agentConversationMapper.insertConversation(UUID.randomUUID(), owner, false, importId);
            if (id == null) {
                return agentConversationMapper.findImport(owner, importId);
            }
            OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);
            for (var turn : turns) {
                agentConversationMapper.insertTurn(UUID.randomUUID(), id,
                        turn.question(), turn.answer(), COMPLETED, timestamp);
            }
            return id;
        });
    }

    private static String status(short status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case FAILED -> "failed";
            case INTERRUPTED -> "interrupted";
            case ACCEPTED -> "accepted";
            default -> "pending";
        };
    }

    private static <T> T requireFound(T value) {
        if (value == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "会话或执行不存在。");
        }
        return value;
    }

}
