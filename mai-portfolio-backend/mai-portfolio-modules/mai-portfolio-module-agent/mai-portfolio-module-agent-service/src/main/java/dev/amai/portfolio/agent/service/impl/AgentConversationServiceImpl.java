package dev.amai.portfolio.agent.service.impl;

import static dev.amai.portfolio.agent.constant.AgentConversationConstants.ACCEPTANCE_WINDOW_SECONDS;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.CONVERSATION_PAGE_SIZE;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.CONVERSATION_TURN_LIMIT;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_DELTA;
import static dev.amai.portfolio.agent.constant.AgentConversationConstants.EVENT_PROGRESS;
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

import dev.amai.portfolio.agent.client.AgentAiClient;
import dev.amai.portfolio.agent.component.AgentGlobalQuota;
import dev.amai.portfolio.agent.entity.AgentConversation;
import dev.amai.portfolio.agent.entity.AgentExecutionSnapshot;
import dev.amai.portfolio.agent.entity.AgentSiteSettings;
import dev.amai.portfolio.agent.entity.AgentTurn;
import dev.amai.portfolio.agent.enums.AgentTurnState;
import dev.amai.portfolio.agent.mapper.AgentConversationMapper;
import dev.amai.portfolio.agent.mapper.AgentTurnMapper;
import dev.amai.portfolio.agent.service.AgentConversationService;
import dev.amai.portfolio.agent.service.AgentSiteSettingsService;
import dev.amai.portfolio.web.exception.ApiErrorCode;
import dev.amai.portfolio.web.exception.ApiException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

@Service
public class AgentConversationServiceImpl implements AgentConversationService {
    private static final int PERSONAL_QUOTA_WINDOW_HOURS = 24;
    private static final String UNAVAILABLE = "模型服务暂不可用。";
    private static final int TITLE_LIMIT = 25;
    private static final DateTimeFormatter DEFAULT_TITLE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final AgentConversationMapper agentConversationMapper;

    private final AgentTurnMapper agentTurnMapper;

    private final AgentAiClient agentAiClient;

    private final TransactionTemplate transactionTemplate;

    private final AgentSiteSettingsService agentSiteSettingsService;

    private final AgentGlobalQuota agentGlobalQuota;

    public AgentConversationServiceImpl(AgentConversationMapper agentConversationMapper,
            AgentTurnMapper agentTurnMapper, AgentAiClient agentAiClient, TransactionTemplate transactionTemplate,
            AgentSiteSettingsService agentSiteSettingsService, AgentGlobalQuota agentGlobalQuota) {
        this.agentConversationMapper = agentConversationMapper;
        this.agentTurnMapper = agentTurnMapper;
        this.agentAiClient = agentAiClient;
        this.transactionTemplate = transactionTemplate;
        this.agentSiteSettingsService = agentSiteSettingsService;
        this.agentGlobalQuota = agentGlobalQuota;
    }

    public boolean enabled() {
        return agentSiteSettingsService.read().agentEnabled();
    }

    public String create(String owner, boolean anonymous) {
        // 登录用户可以创建多个会话，普通插入失败应直接返回错误。
        if (!anonymous) {
            var conversation = new AgentConversation();
            conversation.setOwnerKey(owner);
            var createdAt = OffsetDateTime.now(ZoneOffset.UTC);
            conversation.setCreatedAt(createdAt);
            conversation.setTitle("新会话 " + DEFAULT_TITLE_TIME.format(createdAt));
            agentConversationMapper.insert(conversation);
            return conversation.getId().toString();
        }
        // 同一匿名访客只允许一个会话。并发初始化或回包丢失时复用数据库已有的会话。
        Long inserted = agentConversationMapper.insertAnonymous(owner);
        return requireFound(inserted == null ? agentConversationMapper.findAnonymous(owner) : inserted).toString();
    }

    public ConversationPage list(String owner, Long cursor) {
        var rows = agentConversationMapper.list(owner, cursor, CONVERSATION_PAGE_SIZE + 1);
        var items = rows.stream()
                .limit(CONVERSATION_PAGE_SIZE)
                .map(row -> new ConversationSummary(row.getId().toString(), row.getTitle(), row.getCreatedAt(), row.getUpdatedAt()))
                .toList();
        String nextCursor = rows.size() > CONVERSATION_PAGE_SIZE ? items.getLast().conversationId() : null;
        return new ConversationPage(items, nextCursor);
    }

    /** 登录后把仍归属访客的原会话转给当前账号，问答和计次随原会话继承。 */
    public String claimAnonymous(String visitor, String account) {
        if (visitor == null) {
            return null;
        }
        return transactionTemplate.execute(transaction -> {
            // 1. 先锁访客，再找仍归其所有的会话。重复认领在这里结束。
            agentTurnMapper.lockOwner(visitor);
            Long id = agentConversationMapper.findAnonymous(visitor);
            if (id == null) {
                return null;
            }
            // 2. 再锁账号，使窗口内历史轮次的转移与账号的新受理串行。
            agentTurnMapper.lockOwner(account);
            return agentConversationMapper.claimAnonymous(id, visitor, account) == 1 ? id.toString() : null;
        });
    }

    public ConversationDetails details(Long id, String owner) {
        // 会话详情只对当前所有者开放，未知编号与他人会话统一返回不存在。
        var conversation = agentConversationMapper.findOwned(id, owner);
        if (conversation == null) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "会话或执行不存在。");
        }
        var rows = agentTurnMapper.turns(id, CONVERSATION_TURN_LIMIT);
        var turns = rows.reversed().stream()
                .map(turn -> new TurnView(turn.getRequestId(), executionIdText(turn), turn.getQuestion(), turn.getAnswer(),
                        turn.getStatus().getApiValue(), turn.getErrorMes(), turn.getInputTokens(),
                        turn.getOutputTokens(), turn.getUsageStatus()))
                .toList();
        return new ConversationDetails(id.toString(), conversation.getTitle(), conversation.getCreatedAt(), turns);
    }

    /** 清空当前所有者的会话内容，保留会话及已计轮次。 */
    public ConversationDetails clear(Long id, String owner) {
        return transactionTemplate.execute(transaction -> {
            // 清空与发送共用个人锁，防止检查空闲后插入新的生成轮次。
            agentTurnMapper.lockOwner(owner);
            var conversation = requireFound(agentConversationMapper.findOwned(id, owner));
            if (agentTurnMapper.active(id)) {
                throw new ApiException(ApiErrorCode.DATA_CONFLICT, "请先停止当前生成，再清空聊天内容。");
            }
            String title = conversation.getTitle();
            if (agentTurnMapper.clear(id) > 0) {
                title = "新会话 " + DEFAULT_TITLE_TIME.format(conversation.getCreatedAt());
                agentConversationMapper.resetTitle(id, title);
            }
            return new ConversationDetails(id.toString(), title, conversation.getCreatedAt(), List.of());
        });
    }

    public AgentTurn submit(Long conversation, String owner, boolean anonymous, String requestId, String question) {
        AgentSiteSettings settings = agentSiteSettingsService.read();
        if (!settings.agentEnabled()) {
            throw new ApiException(ApiErrorCode.FORBIDDEN, "网站 AI 功能未开启。");
        }
        // 1. 在个人锁下验证归属、识别重试，并检查新轮次的个人额度。
        StoredSubmission submission = transactionTemplate.execute(transaction -> {
            OffsetDateTime quotaAt = agentTurnMapper.lockOwner(owner);
            requireFound(agentConversationMapper.findOwned(conversation, owner));
            var existing = agentTurnMapper.byRequestId(requestId);
            // 回包丢失时用原请求号、原会话和原问题恢复。换问题必须用新编号，否则会串到旧答复。
            if (existing != null) {
                return new StoredSubmission(matching(existing, conversation, question), false);
            }
            // 每个会话同时只生成一轮。新问题等当前轮结束再发送，避免两轮使用同一份历史。
            if (agentTurnMapper.active(conversation)) {
                throw new ApiException(ApiErrorCode.DATA_CONFLICT, "当前会话已有一轮正在生成。");
            }
            checkPersonalQuota(owner, anonymous, settings, quotaAt);
            // 2. 全站放行后才写正式轮次。写库失败留下的登记随 Redis 窗口自然过期。
            agentGlobalQuota.requirePermit(requestId, settings);
            var pending = new AgentTurn(requestId, conversation, null, question, "",
                    AgentTurnState.PENDING, null, quotaAt.plusSeconds(ACCEPTANCE_WINDOW_SECONDS));
            pending.setQuotaAt(quotaAt);
            // 不同会话仍可能并发提交同一编号。发生冲突时读取胜出的行，按同一输入规则处理。
            int inserted = agentTurnMapper.insertPending(pending);
            if (inserted == 1) {
                String cleaned = question.strip();
                int characters = Math.min(cleaned.codePointCount(0, cleaned.length()), TITLE_LIMIT);
                agentConversationMapper.applyFirstTitle(
                        conversation, cleaned.substring(0, cleaned.offsetByCodePoints(0, characters)));
                agentConversationMapper.touch(conversation);
                return new StoredSubmission(pending, true);
            }
            var stored = agentTurnMapper.byRequestId(requestId);
            return new StoredSubmission(matching(stored, conversation, question), false);
        });
        AgentTurn turn = submission.turn();
        if (turn.getExecutionId() != null) {
            return turn;
        }
        if (turn.getStatus().isTerminal()) {
            throw new ApiException(ApiErrorCode.DATA_CONFLICT, "这次发送未受理，请重新发送。");
        }
        // 3. 先按编号查回已受理执行。只在原截止时间内，以同一输入尝试首次受理。
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

    private void checkPersonalQuota(String owner, boolean anonymous, AgentSiteSettings settings, OffsetDateTime now) {
        int personalLimit = anonymous ? settings.visitorDailyTurnLimit() : settings.accountDailyTurnLimit();
        if (agentTurnMapper.countOwnerQuotaSince(owner, now.minusHours(PERSONAL_QUOTA_WINDOW_HOURS))
                >= personalLimit) {
            throw new ApiException(ApiErrorCode.RATE_LIMITED, "你的对话额度暂已用完，请稍后再试。");
        }
    }

    public Receipt receipt(AgentTurn turn) {
        return new Receipt(turn.getRequestId(), executionIdText(turn), turn.getStatus().getApiValue());
    }

    public AgentTurn ownedExecution(Long execution, String owner) {
        return requireFound(agentTurnMapper.byExecution(execution, owner));
    }

    public AgentExecutionSnapshot snapshot(AgentTurn turn) {
        if (turn.getStatus().isTerminal()) {
            return persisted(turn);
        }
        try {
            return reconcile(turn, agentAiClient.get(executionIdText(turn)));
        } catch (RestClientException error) {
            throw new ApiException(ApiErrorCode.AGENT_UNAVAILABLE, UNAVAILABLE);
        }
    }

    public AgentExecutionSnapshot stop(AgentTurn turn) {
        if (turn.getStatus().isTerminal()) {
            return persisted(turn);
        }
        try {
            return reconcile(turn, agentAiClient.stop(executionIdText(turn)));
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
        agentAiClient.events(executionIdText(turn), (event, data) -> {
            if (EVENT_TERMINAL.equals(event)) {
                // 先提交网站正式结果。持久化失败时关闭流，浏览器按原执行编号恢复。
                var saved = reconcile(turn, agentAiClient.snapshot(data));
                output.accept(event, agentAiClient.json(saved));
            } else if (EVENT_PROGRESS.equals(event)) {
                String progressJson = agentAiClient.publicProgress(data);
                if (progressJson != null) {
                    output.accept(EVENT_PROGRESS, progressJson);
                }
            } else if (EVENT_SNAPSHOT.equals(event) || EVENT_DELTA.equals(event) || EVENT_SOURCES.equals(event)) {
                output.accept(event, data);
            }
        });
    }

    @Scheduled(fixedDelay = RECOVERY_DELAY_MILLIS)
    void recoverUnfinished() {
        // 关闭新请求入口后仍要收尾旧执行。页面关闭也不能漏掉已完成结果。
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
        var found = agentAiClient.findExecution(executionIdText(turn));
        if (found == null) {
            // 上游明确确认执行不存在，只结束当前一轮，后续记录仍可恢复。
            found = new AgentExecutionSnapshot(turn.getRequestId(), executionIdText(turn),
                    AgentTurnState.FAILED.getApiValue(), "", List.of(), new AgentExecutionSnapshot.ExecutionError(
                            EXECUTION_NOT_FOUND, UNAVAILABLE, turn.getRequestId()));
        }
        reconcile(turn, found);
    }

    private static boolean acceptanceExpired(AgentTurn turn) {
        return !OffsetDateTime.now(ZoneOffset.UTC).isBefore(turn.getAcceptBefore());
    }

    private AgentTurn attach(AgentTurn turn, String execution) {
        agentTurnMapper.attach(turn.getRequestId(), Long.valueOf(execution));
        return agentTurnMapper.byRequestId(turn.getRequestId());
    }

    private AgentTurn rejectUnbound(AgentTurn turn) {
        return transactionTemplate.execute(transaction -> {
            if (agentTurnMapper.failUnbound(turn.getRequestId(), "这次发送未受理，请重新发送。") == 1) {
                agentConversationMapper.touch(turn.getConversationId());
            }
            return agentTurnMapper.byRequestId(turn.getRequestId());
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
            int usageStatus = snapshot.usageStatus() == null || snapshot.usageStatus() == 0
                    ? 3 : snapshot.usageStatus();
            if (agentTurnMapper.saveTerminal(
                    turn.getRequestId(), Long.valueOf(snapshot.executionId()), snapshot.text(), terminal,
                    code == null ? null : EXECUTION_TEXT_EXPIRED.equals(code) ? "原回答已无法恢复。" : UNAVAILABLE, snapshot.inputTokens(), snapshot.outputTokens(),
                    usageStatus) == 1) {
                agentConversationMapper.touch(turn.getConversationId());
            }
            return persisted(agentTurnMapper.byRequestId(turn.getRequestId()));
        });
    }

    private AgentExecutionSnapshot persisted(AgentTurn turn) {
        var error = turn.getErrorMes() == null ? null
                : new AgentExecutionSnapshot.ExecutionError("EXECUTION_FAILED", turn.getErrorMes(), turn.getRequestId());
        return new AgentExecutionSnapshot(turn.getRequestId(), executionIdText(turn),
                turn.getStatus().getApiValue(), turn.getAnswer(), List.of(), error,
                turn.getInputTokens(), turn.getOutputTokens(), turn.getUsageStatus());
    }

    private static AgentTurn matching(AgentTurn turn, Long conversation, String question) {
        if (turn == null || !turn.getConversationId().equals(conversation) || !turn.getQuestion().equals(question)) {
            throw new ApiException(ApiErrorCode.REQUEST_ID_CONFLICT, "请求编号已被使用。");
        }
        if (turn.isDeleted()) {
            throw new ApiException(ApiErrorCode.NOT_FOUND, "聊天内容已清空，请重新发送。");
        }
        return turn;
    }

    private static String executionIdText(AgentTurn turn) {
        Long id = turn.getExecutionId();
        return id == null ? null : id.toString();
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
