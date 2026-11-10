package dev.amai.portfolio.agent.service.impl;

import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
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
import java.util.function.BiConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class AgentConversationServiceImpl implements AgentConversationService {
    private static final short PENDING = 0;
    private static final short ACCEPTED = 1;
    private static final short COMPLETED = 3;
    private static final short FAILED = 4;
    private static final short INTERRUPTED = 5;
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
            if (!submission.firstAttempt()) {
                var prior = agentAiClient.byRequest(requestId);
                if (prior != null) {
                    return attach(turn, prior.executionId());
                }
            }
            if (!OffsetDateTime.now(ZoneOffset.UTC).isBefore(turn.acceptBefore())) {
                reject(turn, "ACCEPTANCE_EXPIRED");
                throw new ApiException(ApiErrorCode.DATA_CONFLICT, "发送已超时，请重新发送。");
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
        } catch (RestClientResponseException error) {
            // 只有明确拒绝才结束待确认状态；断网和 5xx 仍可能已经受理。
            if (error.getStatusCode().value() == 429) {
                reject(turn, "AI_CAPACITY");
                throw new ApiException(ApiErrorCode.RATE_LIMITED, UNAVAILABLE);
            }
            if (error.getStatusCode().value() == 422) {
                reject(turn, "AI_INPUT_INVALID");
                throw new ApiException(ApiErrorCode.VALIDATION_FAILED, UNAVAILABLE);
            }
            throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, "暂时无法确认发送结果，请重试原请求。");
        } catch (RestClientException error) {
            throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, "暂时无法确认发送结果，请重试原请求。");
        }
    }

    public Receipt receipt(AgentTurn turn) {
        return new Receipt(turn.requestId(), turn.executionId(), status(turn.status()));
    }

    public AgentTurn ownedExecution(UUID execution, String owner) {
        return requireFound(agentConversationMapper.byExecution(execution, owner));
    }

    public AgentExecutionSnapshot snapshot(AgentTurn turn) {
        if (turn.status() >= COMPLETED) {
            return persisted(turn);
        }
        try {
            return reconcile(turn, agentAiClient.get(turn.executionId()));
        } catch (RestClientException error) {
            throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, UNAVAILABLE);
        }
    }

    public AgentExecutionSnapshot stop(AgentTurn turn) {
        if (turn.status() >= COMPLETED) {
            return persisted(turn);
        }
        try {
            return reconcile(turn, agentAiClient.stop(turn.executionId()));
        } catch (RestClientException error) {
            throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, "暂时无法确认停止结果，请读取原执行。");
        }
    }

    public void events(AgentTurn turn, BiConsumer<String, String> output) {
        if (turn.status() >= COMPLETED) {
            String value = agentAiClient.json(persisted(turn));
            output.accept("snapshot", value);
            output.accept("terminal", value);
            return;
        }
        agentAiClient.events(turn.executionId(), (event, data) -> {
            if ("terminal".equals(event)) {
                // 先提交网站正式结果。持久化失败时关闭流，浏览器按原执行编号恢复。
                var saved = reconcile(turn, agentAiClient.snapshot(data));
                output.accept(event, agentAiClient.json(saved));
            } else if ("snapshot".equals(event) || "delta".equals(event) || "sources".equals(event)) {
                output.accept(event, data);
            }
        });
    }

    @Scheduled(fixedDelayString = "10000")
    void recoverUnfinished() {
        if (!enabled) {
            return;
        }
        // 页面可能在收到受理事件前关闭。后台按持久编号查回，避免已完成结果依赖浏览器。
        for (var turn : agentConversationMapper.unfinished(12)) {
            try {
                if (turn.executionId() != null) {
                    reconcile(turn, agentAiClient.get(turn.executionId()));
                    continue;
                }
                var found = agentAiClient.byRequest(turn.requestId());
                if (found != null) {
                    reconcile(attach(turn, found.executionId()), found);
                } else if (!OffsetDateTime.now(ZoneOffset.UTC).isBefore(turn.acceptBefore())) {
                    reject(turn, "ACCEPTANCE_EXPIRED");
                }
            } catch (RestClientException error) {
                // 依赖暂不可用时保留原状态，下轮用同一编号查证。
                return;
            }
        }
    }

    private AgentTurn attach(AgentTurn turn, UUID execution) {
        agentConversationMapper.attach(turn.requestId(), execution);
        return agentConversationMapper.byRequest(turn.requestId());
    }

    private void reject(AgentTurn turn, String code) {
        transactionTemplate.executeWithoutResult(transaction -> {
            if (agentConversationMapper.saveTerminal(turn.requestId(), "", FAILED, code) == 1) {
                agentConversationMapper.touch(turn.conversationId());
            }
        });
    }

    private AgentExecutionSnapshot reconcile(AgentTurn turn, AgentExecutionSnapshot snapshot) {
        short terminal = switch (snapshot.status()) {
            case "completed" -> COMPLETED;
            case "failed" -> FAILED;
            case "interrupted" -> INTERRUPTED;
            default -> ACCEPTED;
        };
        if (terminal < COMPLETED) {
            return snapshot;
        }
        return transactionTemplate.execute(transaction -> {
            String code = snapshot.error() == null ? null : snapshot.error().code();
            if (agentConversationMapper.saveTerminal(turn.requestId(), snapshot.text(), terminal, code) == 1) {
                agentConversationMapper.touch(turn.conversationId());
            }
            return persisted(agentConversationMapper.byRequest(turn.requestId()));
        });
    }

    private AgentExecutionSnapshot persisted(AgentTurn turn) {
        var error = turn.errorCode() == null ? null
                : new AgentExecutionSnapshot.ExecutionError(turn.errorCode(), UNAVAILABLE, turn.requestId());
        return new AgentExecutionSnapshot(turn.requestId(), turn.executionId(),
                status(turn.status()), turn.answer(), List.of(), error);
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
