package dev.amai.portfolio.agent.service.impl;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.ACCEPTANCE_EXPIRED;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.ACCEPTANCE_WINDOW_SECONDS;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.CONVERSATION_PAGE_SIZE;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.CONVERSATION_TURN_LIMIT;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_DELTA;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_SNAPSHOT;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_SOURCES;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_TERMINAL;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EXECUTION_NOT_FOUND;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EXECUTION_TEXT_EXPIRED;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.HISTORY_TURN_LIMIT;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.MESSAGE_LENGTH_LIMIT;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.RECOVERY_BATCH_SIZE;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.RECOVERY_DELAY_MILLIS;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.ROLE_ASSISTANT;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.ROLE_USER;

import cn.hutool.core.util.IdUtil;
import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.agent.config.AgentQuotaProperties;
import dev.amai.portfolio.agent.entity.AgentConversation;
import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.enums.AgentTurnState;
import dev.amai.portfolio.agent.mapper.AgentConversationMapper;
import dev.amai.portfolio.agent.mapper.AgentTurnMapper;
import dev.amai.portfolio.agent.service.AgentConversationService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

@Service
public class AgentConversationServiceImpl implements AgentConversationService {
    private static final long GLOBAL_QUOTA_LOCK = 7137426010004L;
    private static final int PERSONAL_QUOTA_WINDOW_HOURS = 24;
    private static final String UNAVAILABLE = "模型服务暂不可用。";

    private final AgentConversationMapper agentConversationMapper;

    private final AgentTurnMapper agentTurnMapper;

    private final AgentAiClient agentAiClient;

    private final TransactionTemplate transactionTemplate;

    private final AgentQuotaProperties agentQuotaProperties;

    private final boolean enabled;

    public AgentConversationServiceImpl(AgentConversationMapper agentConversationMapper,
            AgentTurnMapper agentTurnMapper, AgentAiClient agentAiClient, TransactionTemplate transactionTemplate,
            AgentQuotaProperties agentQuotaProperties, @Value("${site-guide.enabled:false}") boolean enabled) {
        this.agentConversationMapper = agentConversationMapper;
        this.agentTurnMapper = agentTurnMapper;
        this.agentAiClient = agentAiClient;
        this.transactionTemplate = transactionTemplate;
        this.agentQuotaProperties = agentQuotaProperties;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public String create(String owner, boolean anonymous) {
        String id = IdUtil.randomUUID();
        // 登录用户可以创建多个会话，普通插入失败应直接返回错误。
        if (!anonymous) {
            var conversation = new AgentConversation();
            conversation.setId(id);
            conversation.setOwnerKey(owner);
            agentConversationMapper.insert(conversation);
            return id;
        }
        // 同一匿名访客只允许一个会话。并发初始化或回包丢失时复用数据库已有的会话。
        String inserted = agentConversationMapper.insertConversation(id, owner, true, null);
        return inserted == null ? agentConversationMapper.findAnonymous(owner) : inserted;
    }

    public ConversationPage list(String owner, String cursor) {
        var rows = agentConversationMapper.list(owner, cursor, CONVERSATION_PAGE_SIZE + 1);
        var items = rows.stream()
                .limit(CONVERSATION_PAGE_SIZE)
                .map(row -> new ConversationSummary(row.getId(), row.getUpdatedAt()))
                .toList();
        String nextCursor = rows.size() > CONVERSATION_PAGE_SIZE ? items.getLast().conversationId() : null;
        return new ConversationPage(items, nextCursor);
    }

    public ConversationDetails details(String id, String owner) {
        // 会话详情只对当前所有者开放，未知编号与他人会话统一返回不存在。
        if (!agentConversationMapper.existsOwned(id, owner)) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "会话或执行不存在。");
        }
        var rows = agentTurnMapper.turns(id, CONVERSATION_TURN_LIMIT);
        var turns = rows.reversed().stream()
                .map(turn -> new TurnView(turn.getRequestId(), turn.getExecutionId(), turn.getQuestion(), turn.getAnswer(),
                        turn.getStatus().getApiValue()))
                .toList();
        return new ConversationDetails(id, turns);
    }

    public String importConversation(String owner, String importId, List<ImportedTurn> turns) {
        return transactionTemplate.execute(transaction -> {
            String id = agentConversationMapper.insertConversation(IdUtil.randomUUID(), owner, false, importId);
            if (id == null) {
                return agentConversationMapper.findImport(owner, importId);
            }
            OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);
            var imported = turns.stream()
                    .map(turn -> new AgentTurn(IdUtil.randomUUID(), id, null,
                            turn.question(), turn.answer(), AgentTurnState.COMPLETED, null, timestamp))
                    .toList();
            agentTurnMapper.insert(imported);
            return id;
        });
    }

    public AgentTurn submit(String conversation, String owner, boolean anonymous, String requestId, String question) {
        if (!enabled) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, "网站 AI 功能未开启。");
        }
        // 1. 先写网站请求与固定截止时间。事务结束后才请求 Python，网络等待期间不持有会话锁。
        StoredSubmission submission = transactionTemplate.execute(transaction -> {
            requireFound(agentConversationMapper.lockConversation(conversation, owner));
            var existing = agentTurnMapper.selectById(requestId);
            // 回包丢失时用原请求号、原会话和原问题恢复。换问题必须用新编号，否则会串到旧答复。
            if (existing != null) {
                return new StoredSubmission(matching(existing, conversation, question), false);
            }
            // 每个会话同时只生成一轮。新问题等当前轮结束再发送，避免两轮使用同一份历史。
            if (agentTurnMapper.active(conversation)) {
                throw new ApiException(ApiErrorCode.DATA_CONFLICT, "当前会话已有一轮正在生成。");
            }
            // 全站和个人额度的判断与新轮次写入共用事务。锁只覆盖受理，调用 Python 前会释放。
            OffsetDateTime quotaAt = checkNewTurnQuota(owner, anonymous);
            var pending = new AgentTurn(requestId, conversation, null, question, "",
                    AgentTurnState.PENDING, null, quotaAt.plusSeconds(ACCEPTANCE_WINDOW_SECONDS));
            pending.setQuotaAt(quotaAt);
            // 不同会话仍可能并发提交同一编号。发生冲突时读取胜出的行，按同一输入规则处理。
            int inserted = agentTurnMapper.insertPending(pending);
            if (inserted == 1) {
                agentConversationMapper.touch(conversation);
                return new StoredSubmission(pending, true);
            }
            var stored = agentTurnMapper.selectById(requestId);
            return new StoredSubmission(matching(stored, conversation, question), false);
        });
        AgentTurn turn = submission.turn();
        if (turn.getExecutionId() != null) {
            return turn;
        }
        if (turn.getStatus().isTerminal()) {
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
                    if (current.getExecutionId() != null) {
                        return current;
                    }
                    throw new ApiException(ApiErrorCode.DATA_CONFLICT, "发送已超时，请重新发送。");
                }
            }
            var history = new ArrayList<AgentAiClient.History>();
            for (var item : agentTurnMapper.recentCompleted(conversation, HISTORY_TURN_LIMIT).reversed()) {
                history.add(new AgentAiClient.History(ROLE_USER, item.getQuestion()));
                if (!item.getAnswer().isBlank()) {
                    history.add(new AgentAiClient.History(ROLE_ASSISTANT, boundedHistory(item.getAnswer())));
                }
            }
            var accepted = agentAiClient.submit(new AgentAiClient.Submission(
                    requestId, question, history, turn.getAcceptBefore()));
            return attach(turn, accepted.executionId());
        } catch (RestClientException error) {
            // 上游拒绝也可能和同编号的受理并发，保留原编号供继续查询。
            throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, "暂时无法确认发送结果，请重试原请求。");
        }
    }

    private OffsetDateTime checkNewTurnQuota(String owner, boolean anonymous) {
        // 所有 Spring 实例共用数据库锁和数据库时钟，避免多个会话同时把剩余全站额度用超。
        OffsetDateTime now = agentTurnMapper.lockQuota(GLOBAL_QUOTA_LOCK);
        OffsetDateTime globalCutoff = now.minusHours(agentQuotaProperties.globalWindowHours());
        if (agentTurnMapper.countQuotaSince(globalCutoff) >= agentQuotaProperties.globalRequestLimit()) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED, "站点对话额度暂已用完，请稍后再试。");
        }
        int personalLimit = anonymous
                ? agentQuotaProperties.visitorDailyTurnLimit()
                : agentQuotaProperties.accountDailyTurnLimit();
        OffsetDateTime personalCutoff = now.minusHours(PERSONAL_QUOTA_WINDOW_HOURS);
        // 只累加当前访客或账号的轮次，同一网络下的其他访客和其他账号各自使用自己的额度。
        if (agentTurnMapper.countOwnerQuotaSince(owner, personalCutoff) >= personalLimit) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED, "你的对话额度暂已用完，请稍后再试。");
        }
        return now;
    }

    public Receipt receipt(AgentTurn turn) {
        return new Receipt(turn.getRequestId(), turn.getExecutionId(), turn.getStatus().getApiValue());
    }

    public AgentTurn ownedExecution(String execution, String owner) {
        return requireFound(agentTurnMapper.byExecution(execution, owner));
    }

    public AgentExecutionSnapshot snapshot(AgentTurn turn) {
        if (turn.getStatus().isTerminal()) {
            return persisted(turn);
        }
        try {
            return reconcile(turn, agentAiClient.get(turn.getExecutionId()));
        } catch (RestClientException error) {
            throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, UNAVAILABLE);
        }
    }

    public AgentExecutionSnapshot stop(AgentTurn turn) {
        if (turn.getStatus().isTerminal()) {
            return persisted(turn);
        }
        try {
            return reconcile(turn, agentAiClient.stop(turn.getExecutionId()));
        } catch (RestClientException error) {
            throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, "暂时无法确认停止结果，请读取原执行。");
        }
    }

    public void events(AgentTurn turn, BiConsumer<String, String> output) {
        if (turn.getStatus().isTerminal()) {
            String value = agentAiClient.json(persisted(turn));
            output.accept(EVENT_SNAPSHOT, value);
            output.accept(EVENT_TERMINAL, value);
            return;
        }
        agentAiClient.events(turn.getExecutionId(), (event, data) -> {
            if (EVENT_TERMINAL.equals(event)) {
                // 先提交网站正式结果。持久化失败时关闭流，浏览器按原执行编号恢复。
                var saved = reconcile(turn, agentAiClient.snapshot(data));
                output.accept(event, agentAiClient.json(saved));
            } else if (EVENT_SNAPSHOT.equals(event) || EVENT_DELTA.equals(event) || EVENT_SOURCES.equals(event)) {
                output.accept(event, data);
            }
        });
    }

    @Scheduled(fixedDelay = RECOVERY_DELAY_MILLIS)
    void recoverUnfinished() {
        if (!enabled) {
            return;
        }
        // 页面可能在收到受理事件前关闭。后台按持久编号查回，避免已完成结果依赖浏览器。
        for (var turn : agentTurnMapper.unfinished(RECOVERY_BATCH_SIZE)) {
            try {
                if (turn.getExecutionId() != null) {
                    recoverBound(turn);
                    continue;
                }
                boolean expired = acceptanceExpired(turn);
                var found = agentAiClient.byRequest(turn.getRequestId());
                if (found != null) {
                    reconcile(attach(turn, found.executionId()), found);
                } else if (expired) {
                    rejectUnbound(turn);
                }
            } catch (RestClientException error) {
                // 依赖暂不可用时保留原状态，下轮用同一编号查证。
                return;
            }
        }
    }

    private void recoverBound(AgentTurn turn) {
        var found = agentAiClient.findExecution(turn.getExecutionId());
        if (found == null) {
            // 上游明确确认执行不存在，只结束当前一轮，后续记录仍可恢复。
            found = new AgentExecutionSnapshot(turn.getRequestId(), turn.getExecutionId(),
                    AgentTurnState.FAILED.getApiValue(), "", List.of(), new AgentExecutionSnapshot.ExecutionError(
                            EXECUTION_NOT_FOUND, UNAVAILABLE, turn.getRequestId()));
        }
        reconcile(turn, found);
    }

    private static boolean acceptanceExpired(AgentTurn turn) {
        return !OffsetDateTime.now(ZoneOffset.UTC).isBefore(turn.getAcceptBefore());
    }

    private AgentTurn attach(AgentTurn turn, String execution) {
        agentTurnMapper.attach(turn.getRequestId(), execution);
        return agentTurnMapper.selectById(turn.getRequestId());
    }

    private AgentTurn rejectUnbound(AgentTurn turn) {
        return transactionTemplate.execute(transaction -> {
            if (agentTurnMapper.failUnbound(turn.getRequestId(), ACCEPTANCE_EXPIRED) == 1) {
                agentConversationMapper.touch(turn.getConversationId());
            }
            return agentTurnMapper.selectById(turn.getRequestId());
        });
    }

    private AgentExecutionSnapshot reconcile(AgentTurn turn, AgentExecutionSnapshot snapshot) {
        String code = snapshot.error() == null ? null : snapshot.error().code();
        // 上游已经清理正文时，网站不能保存一条成功的空答复。
        AgentTurnState terminal = switch (snapshot.status()) {
            case "completed" -> EXECUTION_TEXT_EXPIRED.equals(code) ? AgentTurnState.FAILED : AgentTurnState.COMPLETED;
            case "failed" -> AgentTurnState.FAILED;
            case "interrupted" -> AgentTurnState.INTERRUPTED;
            default -> null;
        };
        if (terminal == null) {
            return snapshot;
        }
        return transactionTemplate.execute(transaction -> {
            if (agentTurnMapper.saveTerminal(
                    turn.getRequestId(), snapshot.executionId(), snapshot.text(), terminal, code) == 1) {
                agentConversationMapper.touch(turn.getConversationId());
            }
            return persisted(agentTurnMapper.selectById(turn.getRequestId()));
        });
    }

    private AgentExecutionSnapshot persisted(AgentTurn turn) {
        var error = turn.getErrorCode() == null ? null
                : new AgentExecutionSnapshot.ExecutionError(turn.getErrorCode(), UNAVAILABLE, turn.getRequestId());
        return new AgentExecutionSnapshot(turn.getRequestId(), turn.getExecutionId(),
                turn.getStatus().getApiValue(), turn.getAnswer(), List.of(), error);
    }

    private static AgentTurn matching(AgentTurn turn, String conversation, String question) {
        if (turn == null || !turn.getConversationId().equals(conversation) || !turn.getQuestion().equals(question)) {
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
        if (text.length() <= MESSAGE_LENGTH_LIMIT) {
            return text;
        }
        int lastIndex = MESSAGE_LENGTH_LIMIT - 1;
        int end = Character.isHighSurrogate(text.charAt(lastIndex)) ? lastIndex : MESSAGE_LENGTH_LIMIT;
        return text.substring(0, end);
    }
}
