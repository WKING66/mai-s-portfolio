package dev.amai.portfolio.agent.service.impl;

import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.mapper.AgentConversationMapper;
import dev.amai.portfolio.agent.service.AgentConversationService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

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

    public AgentTurn submit(UUID conversation, String owner, UUID requestId, String question) {
        if (!enabled) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, "网站 AI 功能未开启。");
        }
        // 1. 先写网站请求与固定截止时间。事务结束后才请求 Python，网络等待期间不持有会话锁。
        StoredSubmission submission = transactionTemplate.execute(transaction -> {
            requireFound(agentConversationMapper.lockConversation(conversation, owner));
            var existing = agentConversationMapper.byRequest(requestId);
            if (existing != null) {
                return new StoredSubmission(matching(existing, conversation, question), false);
            }
            if (agentConversationMapper.active(conversation)) {
                throw new ApiException(ApiErrorCode.DATA_CONFLICT, "当前会话已有一轮正在生成。");
            }
            int inserted = agentConversationMapper.insertTurn(requestId, conversation, question, "", PENDING,
                    OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(45));
            if (inserted == 1) {
                agentConversationMapper.touch(conversation);
            }
            var stored = agentConversationMapper.byRequest(requestId);
            return new StoredSubmission(matching(stored, conversation, question), inserted == 1);
        });
        AgentTurn turn = submission.turn();
        if (turn.executionId() != null) {
            return turn;
        }
        if (turn.status() >= COMPLETED) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "这次发送未受理，请重新发送。");
        }
        // 2. 先按编号查回已受理执行。只在原截止时间内，以同一输入尝试首次受理。
        try {
            // 截止前的负查不能证明其他请求永远不会受理。只有先确认过期再查，才可以落失败终态。
            boolean expired = acceptanceExpired(turn);
            if (expired || !submission.firstAttempt()) {
                var prior = agentAiClient.byRequest(requestId);
                if (prior != null) {
                    return attach(turn, prior.executionId());
                }
                if (expired) {
                    var current = rejectUnbound(turn);
                    if (current.executionId() != null) {
                        return current;
                    }
                    throw new ApiException(ApiErrorCode.DATA_CONFLICT, "发送已超时，请重新发送。");
                }
            }
            var history = new ArrayList<AgentAiClient.History>();
            for (var item : agentConversationMapper.recentCompleted(conversation).reversed()) {
                history.add(new AgentAiClient.History("user", item.question()));
                if (!item.answer().isBlank()) {
                    history.add(new AgentAiClient.History("assistant", boundedHistory(item.answer())));
                }
            }
            var accepted = agentAiClient.submit(new AgentAiClient.Submission(
                    requestId, question, history, turn.acceptBefore()));
            return attach(turn, accepted.executionId());
        } catch (RestClientException error) {
            // 上游拒绝也可能和同编号的受理并发，保留原编号供继续查询。
            throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, "暂时无法确认发送结果，请重试原请求。");
        }
    }

    public Receipt receipt(AgentTurn turn) {
        return new Receipt(turn.requestId(), turn.executionId(), status(turn.status()));
    }

    private static boolean acceptanceExpired(AgentTurn turn) {
        return !OffsetDateTime.now(ZoneOffset.UTC).isBefore(turn.acceptBefore());
    }

    private AgentTurn attach(AgentTurn turn, UUID execution) {
        agentConversationMapper.attach(turn.requestId(), execution);
        return agentConversationMapper.byRequest(turn.requestId());
    }

    private AgentTurn rejectUnbound(AgentTurn turn) {
        return transactionTemplate.execute(transaction -> {
            if (agentConversationMapper.failUnbound(turn.requestId(), FAILED, "ACCEPTANCE_EXPIRED") == 1) {
                agentConversationMapper.touch(turn.conversationId());
            }
            return agentConversationMapper.byRequest(turn.requestId());
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

    private static AgentTurn matching(AgentTurn turn, UUID conversation, String question) {
        if (turn == null || !turn.conversationId().equals(conversation) || !turn.question().equals(question)) {
            throw new ApiException(ApiErrorCode.REQUEST_ID_CONFLICT, "请求编号已被使用。");
        }
        return turn;
    }

    private static <T> T requireFound(T value) {
        if (value == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "会话或执行不存在。");
        }
        return value;
    }

    private record StoredSubmission(
            AgentTurn turn,

            boolean firstAttempt) {}

    private static String boundedHistory(String text) {
        if (text.length() <= 4000) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(3999)) ? 3999 : 4000;
        return text.substring(0, end);
    }
}
